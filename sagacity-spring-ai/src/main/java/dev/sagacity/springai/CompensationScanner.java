package dev.sagacity.springai;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.compensation.CompensationHandler;
import dev.sagacity.core.compensation.CompensationRegistry;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Scans a tool bean for {@code @Tool} methods carrying {@code @Compensable} and
 * registers their compensation methods. Fails fast at wrap time on broken
 * declarations — a missing undo must never be discovered during an incident.
 */
final class CompensationScanner {

	private CompensationScanner() {
	}

	/** Look up the declared reversibility for a tool name. */
	static Reversibility reversibilityFor(Object toolBean, String toolName, CompensationRegistry registry) {
		for (java.lang.reflect.Method method : toolBean.getClass().getDeclaredMethods()) {
			Compensable compensable = method.getAnnotation(Compensable.class);
			if (compensable == null) {
				continue;
			}
			Tool toolAnnotation = method.getAnnotation(Tool.class);
			if (toolAnnotation == null) {
				continue;
			}
			String name = !toolAnnotation.name().isEmpty() ? toolAnnotation.name() : method.getName();
			if (name.equals(toolName)) {
				return compensable.reversibility();
			}
		}
		return Reversibility.COMPENSATABLE; // default
	}

	static void scan(Object toolBean, CompensationRegistry registry) {
		for (Method method : toolBean.getClass().getDeclaredMethods()) {
			Compensable compensable = method.getAnnotation(Compensable.class);
			if (compensable == null) {
				continue;
			}
			if (method.getAnnotation(Tool.class) == null) {
				throw new IllegalStateException("@Compensable on '" + method.getName()
						+ "' requires @Tool on the same method (" + toolBean.getClass().getName() + ")");
			}
			registry.register(new CompensationRegistry.Registration(toolName(method), compensable.reversibility(),
					resolveHandler(toolBean, method, compensable)));
		}
	}

	private static String toolName(Method method) {
		Tool tool = method.getAnnotation(Tool.class);
		return !tool.name().isEmpty() ? tool.name() : method.getName();
	}

	private static CompensationHandler resolveHandler(Object toolBean, Method toolMethod, Compensable compensable) {
		if (compensable.by().isEmpty()) {
			if (compensable.reversibility() == Reversibility.IRREVERSIBLE) {
				return null;
			}
			throw new IllegalStateException("@Compensable on '" + toolMethod.getName()
					+ "' must declare by=\"methodName\" unless reversibility is IRREVERSIBLE");
		}
		Method compensationMethod = Arrays.stream(toolBean.getClass().getDeclaredMethods())
			.filter(m -> m.getName().equals(compensable.by()))
			.filter(m -> m.getParameterCount() == 0
					|| (m.getParameterCount() == 1 && m.getParameterTypes()[0] == CompensationContext.class))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Compensation method '" + compensable.by() + "' for tool '"
					+ toolMethod.getName() + "' not found in " + toolBean.getClass().getName()
					+ " (must take no arguments or a single CompensationContext)"));
		compensationMethod.setAccessible(true);

		return context -> {
			try {
				if (compensationMethod.getParameterCount() == 0) {
					compensationMethod.invoke(toolBean);
				}
				else {
					compensationMethod.invoke(toolBean, context);
				}
			}
			catch (InvocationTargetException ex) {
				throw ex.getCause() instanceof Exception cause ? cause : ex;
			}
		};
	}

}
