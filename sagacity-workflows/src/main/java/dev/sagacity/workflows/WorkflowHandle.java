package dev.sagacity.workflows;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A handle to an in-progress or completed workflow run.
 *
 * <p>Returned by {@link WorkflowRuntime#runAsync}. Allows the caller to poll
 * status, block until completion, or retrieve the failure reason without
 * blocking the thread that submitted the run.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * WorkflowHandle handle = runtime.runAsync(refundWorkflow, orderId);
 *
 * // Non-blocking poll
 * WorkflowStatus status = handle.status();
 *
 * // Block until done (or timeout)
 * handle.awaitCompletion(30, TimeUnit.SECONDS);
 *
 * if (handle.status() == WorkflowStatus.FAILED) {
 *     log.error("Workflow failed: {}", handle.failureReason().orElse("unknown"));
 * }
 * }</pre>
 */
public final class WorkflowHandle {

    private final String runId;
    private final String workflowName;
    private final CompletableFuture<WorkflowRun> future;
    // Mutable reference — updated by the runtime as the run progresses
    private volatile WorkflowRun run;

    WorkflowHandle(String runId, String workflowName, WorkflowRun run, CompletableFuture<WorkflowRun> future) {
        this.runId = runId;
        this.workflowName = workflowName;
        this.run = run;
        this.future = future;
    }

    /** Unique run identifier. */
    public String runId() {
        return runId;
    }

    /** Logical workflow name from {@link dev.sagacity.workflows.annotation.Workflow#value()}. */
    public String workflowName() {
        return workflowName;
    }

    /** Current status of the run. Always reflects the latest known state. */
    public WorkflowStatus status() {
        return run.status();
    }

    /** Whether this run has reached a terminal state (COMPLETED or FAILED). */
    public boolean isDone() {
        WorkflowStatus s = run.status();
        return s == WorkflowStatus.COMPLETED || s == WorkflowStatus.FAILED;
    }

    /**
     * If the run failed, the human-readable reason. Empty if the run is still
     * running or completed successfully.
     */
    public Optional<String> failureReason() {
        return run.failureReason();
    }

    /**
     * Block the calling thread until the run reaches a terminal state.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException     if the run does not complete within the given time
     */
    public void awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        try {
            future.get(timeout, unit);
        } catch (ExecutionException e) {
            // The future itself never fails — failures are captured in WorkflowRun.status()
        }
    }

    /**
     * Block until completion with no timeout. Prefer the timed variant in production.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void awaitCompletion() throws InterruptedException {
        try {
            future.get();
        } catch (ExecutionException e) {
            // Failures are in WorkflowRun.status()
        }
    }

    // Package-private: updated by WorkflowRuntime as the run progresses
    void updateRun(WorkflowRun updated) {
        this.run = updated;
    }
}
