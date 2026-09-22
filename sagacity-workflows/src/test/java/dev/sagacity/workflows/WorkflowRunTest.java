package dev.sagacity.workflows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowRun} — the mutable state object.
 * No Spring context needed.
 */
class WorkflowRunTest {

    @Test
    @DisplayName("new run starts in RUNNING status")
    void newRun_startsRunning() {
        WorkflowRun run = new WorkflowRun("run-1", "test-workflow");
        assertThat(run.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(run.workflowName()).isEqualTo("test-workflow");
        assertThat(run.runId()).isEqualTo("run-1");
    }

    @Test
    @DisplayName("startedAt is set on creation")
    void newRun_startedAtSet() {
        WorkflowRun run = new WorkflowRun("run-1", "test");
        assertThat(run.startedAt()).isNotNull();
    }

    @Test
    @DisplayName("completedAt is empty until terminal state")
    void completedAt_emptyUntilTerminal() {
        WorkflowRun run = new WorkflowRun("run-1", "test");
        assertThat(run.completedAt()).isEmpty();
    }

    @Test
    @DisplayName("completedAt is set when transitioning to COMPLETED")
    void completedAt_setOnCompletion() {
        WorkflowRun run = new WorkflowRun("run-1", "test");
        run.transitionTo(WorkflowStatus.COMPLETED);
        assertThat(run.completedAt()).isPresent();
    }

    @Test
    @DisplayName("completedAt is set when transitioning to FAILED")
    void completedAt_setOnFailure() {
        WorkflowRun run = new WorkflowRun("run-1", "test");
        run.fail("something went wrong");
        assertThat(run.completedAt()).isPresent();
    }

    @Test
    @DisplayName("setPendingGate transitions to PAUSED_AT_GATE")
    void setPendingGate_transitionsToPaused() {
        WorkflowRun run = new WorkflowRun("run-1", "test");
        run.setPendingGate("approvalStage");
        assertThat(run.status()).isEqualTo(WorkflowStatus.PAUSED_AT_GATE);
        assertThat(run.pendingGateStageName()).isPresent().hasValue("approvalStage");
    }

    @Test
    @DisplayName("clearPendingGate removes the gate name")
    void clearPendingGate_clearsName() {
        WorkflowRun run = new WorkflowRun("run-1", "test");
        run.setPendingGate("approvalStage");
        run.clearPendingGate();
        assertThat(run.pendingGateStageName()).isEmpty();
    }

    @Test
    @DisplayName("completedStages is unmodifiable from outside")
    void completedStages_unmodifiable() {
        WorkflowRun run = new WorkflowRun("run-1", "test");
        run.recordStageCompleted("stage1");
        assertThat(run.completedStages()).containsExactly("stage1");
        // Verify it's a copy — mutations don't affect internal state
        var stages = run.completedStages();
        assertThat(stages).hasSize(1);
    }

    @Test
    @DisplayName("fail captures reason and transitions to FAILED")
    void fail_capturesReason() {
        WorkflowRun run = new WorkflowRun("run-1", "test");
        run.fail("network timeout");
        assertThat(run.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.failureReason()).isPresent().hasValue("network timeout");
    }
}
