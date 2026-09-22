package dev.sagacity.workflows;

/**
 * Lifecycle states of a workflow run.
 *
 * <p>State transitions:
 * <pre>
 *   RUNNING ──────────────────────────────────► COMPLETED
 *      │                                             ▲
 *      ├──► PAUSED_AT_GATE ──(approved)──────────────┤
 *      │         │                                   │
 *      │         └──(rejected/timeout)──► FAILED     │
 *      │                                    ▲        │
 *      └──(stage fails / check fails)──────►│        │
 *                                           │        │
 *                                    COMPENSATING ───┘
 *                                    (after FAILED)
 * </pre>
 */
public enum WorkflowStatus {

    /** At least one stage is actively executing. */
    RUNNING,

    /**
     * The workflow has reached a {@link dev.sagacity.workflows.annotation.Gate}
     * with {@code approvalRequired = true} and is waiting for a human decision.
     * No stage is executing. The run resumes when approved or fails when rejected.
     */
    PAUSED_AT_GATE,

    /**
     * A stage or check failed, or a gate was rejected. Compensation is currently
     * running in reverse stage order.
     */
    COMPENSATING,

    /** All stages completed successfully. This is a terminal state. */
    COMPLETED,

    /**
     * The workflow failed and compensation has finished (or there was nothing
     * to compensate). This is a terminal state.
     */
    FAILED
}
