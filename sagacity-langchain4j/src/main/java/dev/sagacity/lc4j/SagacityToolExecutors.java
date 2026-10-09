package dev.sagacity.lc4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.sagacity.control.ApprovalStore;
import dev.sagacity.control.InMemoryApprovalStore;
import dev.sagacity.recovery.CompensationRegistry;
import dev.sagacity.recovery.CompensationRunner;
import dev.sagacity.audit.AuditStore;
import dev.sagacity.audit.InMemoryAuditStore;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Entry point for Sagacity LangChain4j integration.
 *
 * <p>Wraps the {@code @Tool} methods of one or more beans with Sagacity governance
 * and returns a {@code Map<String, ToolExecutor>} ready for
 * {@code AiServices.builder().toolExecutors(map)}.
 *
 * <h2>Basic usage</h2>
 * <pre>{@code
 * AuditStore auditStore = new JdbcAuditStore(dataSource);
 *
 * Map<String, ToolExecutor> tools = SagacityToolExecutors.wrap(
 *     auditStore, new BookingTools());
 *
 * TravelAgent agent = AiServices.builder(TravelAgent.class)
 *     .chatLanguageModel(model)
 *     .toolExecutors(tools)
 *     .build();
 * }</pre>
 *
 * <h2>Full builder</h2>
 * <pre>{@code
 * Map<String, ToolExecutor> tools = SagacityToolExecutors.builder()
 *     .auditStore(new JdbcAuditStore(dataSource))
 *     .toolBeans(new BookingTools(), new PaymentTools())
 *     .build();
 * }</pre>
 *
 * <h2>Saga scoping</h2>
 * <p>The LangChain4j chat memory ID ({@code memoryId} passed to each tool executor)
 * is used as the saga ID. One conversation = one saga. All tool calls in the same
 * conversation share the same audit trail and compensation scope.
 *
 * <p>If {@code memoryId} is null the tool executes as a plain pass-through with
 * no journaling — preserving compatibility with non-memory LangChain4j flows.
 */
public final class SagacityToolExecutors {

    private SagacityToolExecutors() {}

    /**
     * Wraps all {@code @Tool} methods on the given beans using an in-memory audit store.
     * Suitable for development and testing only — data is lost on JVM restart.
     *
     * @param toolBeans one or more objects with {@code @Tool}-annotated methods
     * @return map of tool name → wrapped executor
     */
    public static Map<String, ToolExecutor> wrap(Object... toolBeans) {
        return wrap(new InMemoryAuditStore(), toolBeans);
    }

    /**
     * Wraps all {@code @Tool} methods on the given beans with a durable audit store.
     *
     * @param auditStore the store to write audit entries to
     * @param toolBeans  one or more objects with {@code @Tool}-annotated methods
     * @return map of tool name → wrapped executor
     */
    public static Map<String, ToolExecutor> wrap(AuditStore auditStore, Object... toolBeans) {
        return builder().auditStore(auditStore).toolBeans(toolBeans).build();
    }

    /** Returns a builder for full control over stores and retry configuration. */
    public static Builder builder() {
        return new Builder();
    }

    // ── Builder ────────────────────────────────────────────────────────────

    /**
     * Builder for {@link SagacityToolExecutors}.
     */
    public static final class Builder {

        private AuditStore auditStore = new InMemoryAuditStore();
        private ApprovalStore approvalStore = new InMemoryApprovalStore();
        private long retryInitialDelayMs = 100L;
        private double retryBackoffMultiplier = 2.0;
        private Object[] toolBeans = new Object[0];

        private Builder() {}

        /** Sets the audit store (default: {@link InMemoryAuditStore}). */
        public Builder auditStore(AuditStore auditStore) {
            this.auditStore = auditStore;
            return this;
        }

        /** Sets the approval store (default: {@link InMemoryApprovalStore}). */
        public Builder approvalStore(ApprovalStore approvalStore) {
            this.approvalStore = approvalStore;
            return this;
        }

        /** Initial backoff delay in ms for retry (default: 100). */
        public Builder retryInitialDelayMs(long retryInitialDelayMs) {
            this.retryInitialDelayMs = retryInitialDelayMs;
            return this;
        }

        /** Exponential backoff multiplier for retry (default: 2.0). */
        public Builder retryBackoffMultiplier(double retryBackoffMultiplier) {
            this.retryBackoffMultiplier = retryBackoffMultiplier;
            return this;
        }

        /** Sets the tool beans to wrap. */
        public Builder toolBeans(Object... toolBeans) {
            this.toolBeans = toolBeans;
            return this;
        }

        /**
         * Scans all tool beans, registers their {@code @Compensable} declarations,
         * and returns an immutable map of tool name → wrapped executor.
         *
         * @throws IllegalStateException if a {@code @Compensable(by=...)} names a
         *                               compensation method that does not exist
         */
        public Map<String, ToolExecutor> build() {
            CompensationRegistry registry = new CompensationRegistry();
            CompensationRunner runner = new CompensationRunner(auditStore, registry);

            Map<String, ToolExecutor> executors = new HashMap<>();

            for (Object bean : toolBeans) {
                // Register @Compensable declarations — fails fast on broken refs
                Lc4jCompensationScanner.scan(bean, registry);

                for (Method method : bean.getClass().getDeclaredMethods()) {
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    if (toolAnnotation == null) continue;

                    String toolName = deriveToolName(toolAnnotation, method);
                    var retryPolicy = Lc4jCompensationScanner.retryPolicyFor(
                            bean, toolName, retryInitialDelayMs, retryBackoffMultiplier);

                    executors.put(toolName, new SagacityToolExecutor(
                            bean, method, toolName, auditStore, runner, retryPolicy));
                }
            }

            return Map.copyOf(executors);
        }

        private static String deriveToolName(Tool annotation, Method method) {
            if (annotation.value() != null && annotation.value().length > 0
                    && !annotation.value()[0].isBlank()) {
                return annotation.value()[0];
            }
            return method.getName();
        }
    }
}
