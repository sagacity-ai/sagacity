package dev.sagacity.workflows;

/**
 * Thrown when a {@link dev.sagacity.workflows.annotation.Workflow}-annotated class
 * has a structural problem detected at startup or runtime.
 *
 * <p>Examples: missing {@code @Stage} methods, duplicate stage orders,
 * {@code @Compensable(by = "x")} with no matching {@code @Compensation} method named {@code x}.
 *
 * <p>This is always a programmer error — it should never be caught and swallowed.
 */
public class WorkflowDefinitionException extends RuntimeException {

    public WorkflowDefinitionException(String message) {
        super(message);
    }

    public WorkflowDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
