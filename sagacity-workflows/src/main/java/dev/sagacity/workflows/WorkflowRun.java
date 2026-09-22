package dev.sagacity.workflows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Mutable in-memory state for a single workflow execution.
 *
 * <p>One instance is created per {@link WorkflowRuntime#run} call and held in the
 * runtime's in-memory store keyed by {@link #runId()}. All mutation is done under
 * the runtime's lock — this class is not thread-safe on its own.
 */
public final class WorkflowRun {

    private final String runId;
    private final String workflowName;
    private final Instant startedAt;
    private WorkflowStatus status;
    private int currentStageOrder;
    private String pendingGateStageName;
    private Object lastStageOutput;
    private String failureReason;
    private Instant completedAt;

    // Ordered record of completed stage names (for resume and compensation ordering)
    private final List<String> completedStages = new ArrayList<>();

    public WorkflowRun(String runId, String workflowName) {
        this.runId = runId;
        this.workflowName = workflowName;
        this.startedAt = Instant.now();
        this.status = WorkflowStatus.RUNNING;
    }

    public String runId() { return runId; }
    public String workflowName() { return workflowName; }
    public Instant startedAt() { return startedAt; }
    public WorkflowStatus status() { return status; }
    public int currentStageOrder() { return currentStageOrder; }
    public Optional<String> pendingGateStageName() { return Optional.ofNullable(pendingGateStageName); }
    public Object lastStageOutput() { return lastStageOutput; }
    public Optional<String> failureReason() { return Optional.ofNullable(failureReason); }
    public Optional<Instant> completedAt() { return Optional.ofNullable(completedAt); }
    public List<String> completedStages() { return Collections.unmodifiableList(completedStages); }

    void transitionTo(WorkflowStatus next) {
        this.status = next;
        if (next == WorkflowStatus.COMPLETED || next == WorkflowStatus.FAILED) {
            this.completedAt = Instant.now();
        }
    }

    void setCurrentStageOrder(int order) {
        this.currentStageOrder = order;
    }

    void setPendingGate(String stageName) {
        this.pendingGateStageName = stageName;
        transitionTo(WorkflowStatus.PAUSED_AT_GATE);
    }

    void clearPendingGate() {
        this.pendingGateStageName = null;
    }

    void setLastStageOutput(Object output) {
        this.lastStageOutput = output;
    }

    void recordStageCompleted(String stageName) {
        completedStages.add(stageName);
    }

    void fail(String reason) {
        this.failureReason = reason;
        transitionTo(WorkflowStatus.FAILED);
    }
}
