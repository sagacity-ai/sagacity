package dev.sagacity.lc4j;

import dev.langchain4j.agent.tool.Tool;
import dev.sagacity.core.Reversibility;
import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.compensation.CompensationHandler;
import dev.sagacity.core.compensation.CompensationRegistry;
import dev.sagacity.core.retry.RetryPolicy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Scans a LangChain4j tool bean for {@code @Tool} methods carrying
 * {@code @Compensable} and registers their compensation handlers.
 *
 * <p>Fails fast at scan time on broken declarations — a missing compensation
 * method must never be discovered during a production incident.
 *
 * <p>This is the LangChain4j counterpart of the Spring AI {@code CompensationScanner}.
 * It uses {@link dev.langchain4j.agent.tool.Tool} rather than the Spring AI annotation.
 */
final class Lc4jCompensationScanner {

    private Lc4jCompensationScanner() {}

    /**
     * Scans all {@code @Tool} + {@code @Compensable} methods on the bean,
     * resolves their compensation handlers, and registers them.
     *
     * @throws IllegalStateException if a declared compensation method is missing
     *                               or a {@code @Compensable} is missing {@code @Tool}
     */
    static void scan(Object toolBean, CompensationRegistry registry) {
        for (Method method : toolBean.getClass().getDeclaredMethods()) {
            Compensable compensable = method.getAnnotation(Compensable.class);
            if (compensable == null) continue;

            if (method.getAnnotation(Tool.class) == null) {
                throw new IllegalStateException(
                        "@Compensable on '" + method.getName()
                        + "' requires @Tool on the same method ("
                        + toolBean.getClass().getName() + ")");
            }

            registry.register(new CompensationRegistry.Registration(
                    toolName(method),
                    compensable.reversibility(),
                    resolveHandler(toolBean, method, compensable)));
        }
    }

    /**
     * Returns the retry policy declared on the {@code @Compensable} for the named tool.
     * Returns {@link RetryPolicy#NONE} if not declared.
     */
    static RetryPolicy retryPolicyFor(Object toolBean, String toolName,
            long initialDelayMs, double backoffMultiplier) {
        for (Method method : toolBean.getClass().getDeclaredMethods()) {
            Compensable compensable = method.getAnnotation(Compensable.class);
            if (compensable == null) continue;
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) continue;
            if (!toolName(method).equals(toolName)) continue;

            int retries = compensable.retries();
            Class<? extends Throwable>[] retryOn = compensable.retryOn();
            if (retries <= 0 || retryOn.length == 0) return RetryPolicy.NONE;
            return new RetryPolicy(retries + 1, retryOn, initialDelayMs, backoffMultiplier);
        }
        return RetryPolicy.NONE;
    }

    /**
     * Returns the declared reversibility for the named tool.
     * Returns {@link Reversibility#COMPENSATABLE} if not declared.
     */
    static Reversibility reversibilityFor(Object toolBean, String toolName) {
        for (Method method : toolBean.getClass().getDeclaredMethods()) {
            Compensable compensable = method.getAnnotation(Compensable.class);
            if (compensable == null) continue;
            if (method.getAnnotation(Tool.class) == null) continue;
            if (!toolName(method).equals(toolName)) continue;
            return compensable.reversibility();
        }
        return Reversibility.COMPENSATABLE;
    }

    // ── private helpers ────────────────────────────────────────────────────

    private static String toolName(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        // LangChain4j @Tool.value() is a String[], first element is the name
        if (tool.value() != null && tool.value().length > 0 && !tool.value()[0].isBlank()) {
            return tool.value()[0];
        }
        return method.getName();
    }

    private static CompensationHandler resolveHandler(Object bean, Method toolMethod,
            Compensable compensable) {
        if (compensable.by().isEmpty()) {
            if (compensable.reversibility() == Reversibility.IRREVERSIBLE) {
                return null; // IRREVERSIBLE tools have no compensation
            }
            throw new IllegalStateException(
                    "@Compensable on '" + toolMethod.getName()
                    + "' must declare by=\"methodName\" unless reversibility is IRREVERSIBLE");
        }

        Method compensationMethod = Arrays.stream(bean.getClass().getDeclaredMethods())
                .filter(m -> m.getName().equals(compensable.by()))
                .filter(m -> m.getParameterCount() == 0
                        || (m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == CompensationContext.class))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Compensation method '" + compensable.by() + "' for tool '"
                        + toolMethod.getName() + "' not found in "
                        + bean.getClass().getName()
                        + " (must take no arguments or a single CompensationContext)"));

        compensationMethod.setAccessible(true);

        return context -> {
            try {
                if (compensationMethod.getParameterCount() == 0) {
                    compensationMethod.invoke(bean);
                } else {
                    compensationMethod.invoke(bean, context);
                }
            } catch (InvocationTargetException ex) {
                throw ex.getCause() instanceof Exception cause ? cause : ex;
            } catch (IllegalAccessException ex) {
                throw new RuntimeException("Cannot invoke compensation method '"
                        + compensationMethod.getName() + "'", ex);
            }
        };
    }
}
