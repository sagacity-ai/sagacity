package dev.sagacity.workflows.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a human approval gate on a {@link Stage} method.
 *
 * <p>When a stage is annotated with {@code @Gate(approvalRequired = true)}, the
 * workflow pauses before executing that stage and emits an approval request. The
 * workflow transitions to {@link dev.sagacity.workflows.WorkflowStatus#PAUSED_AT_GATE}
 * and waits. Execution only resumes when the gate is approved via the REST endpoint
 * or programmatically via {@link dev.sagacity.workflows.WorkflowRuntime#approveGate}.
 *
 * <p>If the gate is rejected, the workflow transitions to
 * {@link dev.sagacity.workflows.WorkflowStatus#FAILED} and compensation runs for
 * all previously completed stages.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Stage(order = 3)
 * @Gate(approvalRequired = true, timeoutSeconds = 3600)
 * @Compensable(by = "revertEmail")
 * public void sendEmail(EmailDraft draft) { ... }
 * }</pre>
 *
 * <p>The gate is a first-class workflow state, not an exception path. The approval
 * request contains the workflow run ID, stage name, and a JSON summary of the stage
 * input — enough context for a human to make an informed decision.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Gate {

    /**
     * When {@code true}, the workflow pauses before this stage and waits for
     * explicit approval. When {@code false} (default), the gate is informational
     * only and execution continues immediately.
     */
    boolean approvalRequired() default false;

    /**
     * How long (in seconds) to wait for approval before the gate times out.
     * {@code 0} means no timeout — the workflow waits indefinitely.
     * When a timeout fires, the workflow transitions to FAILED and compensates.
     */
    long timeoutSeconds() default 0;

    /**
     * Reason shown to the approver in the UI and audit trail.
     * Use this to explain why human oversight is needed at this stage.
     */
    String reason() default "";
}
