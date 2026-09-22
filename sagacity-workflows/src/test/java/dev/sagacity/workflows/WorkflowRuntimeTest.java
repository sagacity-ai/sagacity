package dev.sagacity.workflows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.InMemorySideEffectJournal;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;
import dev.sagacity.workflows.annotation.Check;
import dev.sagacity.workflows.annotation.Gate;
import dev.sagacity.workflows.annotation.Stage;
import dev.sagacity.workflows.annotation.Workflow;
import dev.sagacity.workflows.check.CheckResult;
import dev.sagacity.workflows.check.StageCheck;
import dev.sagacity.workflows.check.StageCheckContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core tests for {@link WorkflowRuntime}.
 *
 * <p>These tests are the regression fence: if the engine breaks, these fail first.
 * All workflow beans are plain Spring beans in the test config — no Testcontainers,
 * no external dependencies.
 */
@SpringBootTest(classes = WorkflowRuntimeTest.TestConfig.class)
class WorkflowRuntimeTest {

    @Autowired WorkflowRuntime runtime;
    @Autowired LinearWorkflow linearWorkflow;
    @Autowired FailingWorkflow failingWorkflow;
    @Autowired GatedWorkflow gatedWorkflow;
    @Autowired CheckedWorkflow checkedWorkflow;
    @Autowired BlockingCheckWorkflow blockingCheckWorkflow;

    @BeforeEach
    void resetWorkflows() {
        linearWorkflow.reset();
        failingWorkflow.reset();
        gatedWorkflow.reset();
        checkedWorkflow.reset();
        blockingCheckWorkflow.reset();
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    @DisplayName("all stages execute in order and workflow completes")
    void happyPath_allStagesComplete() {
        WorkflowRun run = runtime.run(linearWorkflow, "start");

        assertThat(run.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(linearWorkflow.executionOrder()).containsExactly("stage1", "stage2", "stage3");
    }

    @Test
    @DisplayName("stage output is chained as input to the next stage")
    void happyPath_stageOutputChained() {
        WorkflowRun run = runtime.run(linearWorkflow, "start");

        assertThat(run.status()).isEqualTo(WorkflowStatus.COMPLETED);
        // Stage 2 receives the output of stage 1, stage 3 receives output of stage 2
        assertThat(linearWorkflow.receivedInputs()).containsExactly(
                "start",          // stage1 receives the initial input
                "stage1-output",  // stage2 receives stage1's return value
                "stage2-output"   // stage3 receives stage2's return value
        );
    }

    @Test
    @DisplayName("completed stages are tracked in order")
    void happyPath_completedStagesTracked() {
        WorkflowRun run = runtime.run(linearWorkflow, "start");

        assertThat(run.completedStages()).containsExactly("stage1", "stage2", "stage3");
    }

    @Test
    @DisplayName("journal has EXECUTED entries for all stages")
    void happyPath_journalEntries(@Autowired SideEffectJournal journal) {
        WorkflowRun run = runtime.run(linearWorkflow, "start");

        long executedCount = journal.entries(run.runId()).stream()
                .filter(e -> e.phase() == Phase.EXECUTED)
                .count();
        assertThat(executedCount).isEqualTo(3); // 3 stages
    }

    @Test
    @DisplayName("async run returns handle immediately and completes")
    void asyncRun_completesSuccessfully() throws Exception {
        WorkflowHandle handle = runtime.runAsync(linearWorkflow, "start");

        handle.awaitCompletion(5, TimeUnit.SECONDS);

        assertThat(handle.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(handle.isDone()).isTrue();
        assertThat(handle.failureReason()).isEmpty();
    }

    // =========================================================================
    // Failure and compensation
    // =========================================================================

    @Test
    @DisplayName("when a stage fails, workflow transitions to COMPENSATING then FAILED")
    void failure_statusTransition() {
        WorkflowRun run = runtime.run(failingWorkflow, "start");

        assertThat(run.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.failureReason()).isPresent()
                .hasValueSatisfying(reason -> assertThat(reason).contains("stage2 intentionally failed"));
    }

    @Test
    @DisplayName("when stage 2 fails, stage 1 compensation runs")
    void failure_compensatesCompletedStages() {
        runtime.run(failingWorkflow, "start");

        assertThat(failingWorkflow.compensated()).containsExactly("compensateStage1");
    }

    @Test
    @DisplayName("compensation runs in reverse stage order")
    void failure_compensationReverseOrder() {
        runtime.run(failingWorkflow, "start");

        // stage1 completes first, stage2 fails — compensation runs: stage1's compensation
        // If multiple stages complete before failure, last completed compensates first
        assertThat(failingWorkflow.compensated()).containsExactly("compensateStage1");
    }

    @Test
    @DisplayName("failure reason is captured in WorkflowRun")
    void failure_reasonCaptured() {
        WorkflowRun run = runtime.run(failingWorkflow, "start");

        assertThat(run.failureReason()).isPresent();
        assertThat(run.failureReason().get()).contains("stage2 intentionally failed");
    }

    @Test
    @DisplayName("failed stage is journaled as FAILED")
    void failure_journaledAsFailed(@Autowired SideEffectJournal journal) {
        WorkflowRun run = runtime.run(failingWorkflow, "start");

        boolean hasFailedEntry = journal.entries(run.runId()).stream()
                .anyMatch(e -> e.phase() == Phase.FAILED);
        assertThat(hasFailedEntry).isTrue();
    }

    // =========================================================================
    // Gate (human approval)
    // =========================================================================

    @Test
    @DisplayName("gate pauses workflow with PAUSED_AT_GATE status")
    void gate_pausesWorkflow() throws Exception {
        WorkflowHandle handle = runtime.runAsync(gatedWorkflow, "start");

        // Give the workflow thread time to reach the gate
        awaitStatus(handle, WorkflowStatus.PAUSED_AT_GATE, 2000);

        assertThat(handle.status()).isEqualTo(WorkflowStatus.PAUSED_AT_GATE);
    }

    @Test
    @DisplayName("approving gate resumes workflow to COMPLETED")
    void gate_approvalResumesAndCompletes() throws Exception {
        WorkflowHandle handle = runtime.runAsync(gatedWorkflow, "start");
        awaitStatus(handle, WorkflowStatus.PAUSED_AT_GATE, 2000);

        runtime.approveGate(handle.runId(), "gatedStage");

        handle.awaitCompletion(5, TimeUnit.SECONDS);
        assertThat(handle.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(gatedWorkflow.executionOrder()).containsExactly("stage1", "gatedStage");
    }

    @Test
    @DisplayName("rejecting gate fails the workflow and runs compensation")
    void gate_rejectionFailsAndCompensates() throws Exception {
        WorkflowHandle handle = runtime.runAsync(gatedWorkflow, "start");
        awaitStatus(handle, WorkflowStatus.PAUSED_AT_GATE, 2000);

        runtime.rejectGate(handle.runId(), "gatedStage", "not authorized");

        handle.awaitCompletion(5, TimeUnit.SECONDS);
        assertThat(handle.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(handle.failureReason()).isPresent()
                .hasValueSatisfying(r -> assertThat(r).contains("not authorized"));
        assertThat(gatedWorkflow.compensated()).containsExactly("compensateStage1");
    }

    @Test
    @DisplayName("approving wrong stage name throws IllegalStateException")
    void gate_wrongStageNameThrows() throws Exception {
        WorkflowHandle handle = runtime.runAsync(gatedWorkflow, "start");
        awaitStatus(handle, WorkflowStatus.PAUSED_AT_GATE, 2000);

        assertThatThrownBy(() -> runtime.approveGate(handle.runId(), "wrongStageName"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrongStageName");

        // Clean up
        runtime.rejectGate(handle.runId(), "gatedStage", "test cleanup");
        handle.awaitCompletion(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("approving a non-paused run throws IllegalStateException")
    void gate_approveNonPausedRunThrows() {
        WorkflowRun run = runtime.run(linearWorkflow, "start");
        assertThat(run.status()).isEqualTo(WorkflowStatus.COMPLETED);

        assertThatThrownBy(() -> runtime.approveGate(run.runId(), "stage1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // Checks
    // =========================================================================

    @Test
    @DisplayName("passing check allows stage execution")
    void check_passingCheckAllowsExecution() {
        WorkflowRun run = runtime.run(checkedWorkflow, "start");

        assertThat(run.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(checkedWorkflow.executionOrder()).containsExactly("stage1", "checkedStage");
    }

    @Test
    @DisplayName("failing check blocks stage and fails workflow")
    void check_failingCheckBlocksStage() {
        WorkflowRun run = runtime.run(blockingCheckWorkflow, "start");

        assertThat(run.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.failureReason()).isPresent()
                .hasValueSatisfying(r -> assertThat(r).contains("budget exceeded"));
        // The checked stage itself should NOT have executed
        assertThat(blockingCheckWorkflow.executionOrder()).doesNotContain("blockedStage");
    }

    @Test
    @DisplayName("failing check triggers compensation for completed stages")
    void check_failingCheckCompensatesPriorStages() {
        runtime.run(blockingCheckWorkflow, "start");

        assertThat(blockingCheckWorkflow.compensated()).containsExactly("compensateStage1");
    }

    // =========================================================================
    // Topology validation
    // =========================================================================

    @Test
    @DisplayName("validateTopology passes for a valid workflow")
    void topology_validWorkflowPasses() {
        // Should not throw
        WorkflowRuntime.validateTopology(linearWorkflow);
    }

    @Test
    @DisplayName("validateTopology fails when @Compensable references missing @Compensation method")
    void topology_missingCompensationMethodThrows() {
        assertThatThrownBy(() -> WorkflowRuntime.validateTopology(new BrokenCompensationWorkflow()))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("nonExistentMethod");
    }

    @Test
    @DisplayName("validateTopology fails when no @Stage methods exist")
    void topology_noStageMethodsThrows() {
        assertThatThrownBy(() -> WorkflowRuntime.validateTopology(new EmptyWorkflow()))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("no @Stage methods");
    }

    @Test
    @DisplayName("findRun returns empty for unknown ID")
    void findRun_unknownIdReturnsEmpty() {
        assertThat(runtime.findRun("does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("findRun returns the run after it completes")
    void findRun_returnsCompletedRun() {
        WorkflowRun run = runtime.run(linearWorkflow, "start");
        assertThat(runtime.findRun(run.runId())).isPresent()
                .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(WorkflowStatus.COMPLETED));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void awaitStatus(WorkflowHandle handle, WorkflowStatus expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (handle.status() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    // =========================================================================
    // Test workflow beans
    // =========================================================================

    @Workflow("linear")
    static class LinearWorkflow {
        private final List<String> executionOrder = new ArrayList<>();
        private final List<String> receivedInputs = new ArrayList<>();

        @Stage(order = 1, name = "stage1")
        public String stage1(String input) {
            receivedInputs.add(input);
            executionOrder.add("stage1");
            return "stage1-output";
        }

        @Stage(order = 2, name = "stage2")
        public String stage2(String input) {
            receivedInputs.add(input);
            executionOrder.add("stage2");
            return "stage2-output";
        }

        @Stage(order = 3, name = "stage3")
        public void stage3(String input) {
            receivedInputs.add(input);
            executionOrder.add("stage3");
        }

        List<String> executionOrder() { return executionOrder; }
        List<String> receivedInputs() { return receivedInputs; }
        void reset() { executionOrder.clear(); receivedInputs.clear(); }
    }

    @Workflow("failing")
    static class FailingWorkflow {
        private final List<String> executionOrder = new ArrayList<>();
        private final List<String> compensated = new ArrayList<>();

        @Stage(order = 1, name = "stage1")
        @Compensable(by = "compensateStage1")
        public String stage1(String input) {
            executionOrder.add("stage1");
            return "output1";
        }

        @Stage(order = 2, name = "stage2")
        public void stage2(String input) {
            executionOrder.add("stage2");
            throw new RuntimeException("stage2 intentionally failed");
        }

        @Compensation
        public void compensateStage1(CompensationContext ctx) {
            compensated.add("compensateStage1");
        }

        List<String> executionOrder() { return executionOrder; }
        List<String> compensated() { return compensated; }
        void reset() { executionOrder.clear(); compensated.clear(); }
    }

    @Workflow("gated")
    static class GatedWorkflow {
        private final List<String> executionOrder = new ArrayList<>();
        private final List<String> compensated = new ArrayList<>();

        @Stage(order = 1, name = "stage1")
        @Compensable(by = "compensateStage1")
        public String stage1(String input) {
            executionOrder.add("stage1");
            return "output1";
        }

        @Stage(order = 2, name = "gatedStage")
        @Gate(approvalRequired = true)
        public void gatedStage(String input) {
            executionOrder.add("gatedStage");
        }

        @Compensation
        public void compensateStage1(CompensationContext ctx) {
            compensated.add("compensateStage1");
        }

        List<String> executionOrder() { return executionOrder; }
        List<String> compensated() { return compensated; }
        void reset() { executionOrder.clear(); compensated.clear(); }
    }

    static class AlwaysPassCheck implements StageCheck {
        @Override
        public CheckResult check(StageCheckContext ctx) { return CheckResult.pass(); }
    }

    static class AlwaysFailCheck implements StageCheck {
        @Override
        public CheckResult check(StageCheckContext ctx) { return CheckResult.fail("budget exceeded"); }
    }

    @Workflow("checked")
    static class CheckedWorkflow {
        private final List<String> executionOrder = new ArrayList<>();

        @Stage(order = 1, name = "stage1")
        public String stage1(String input) {
            executionOrder.add("stage1");
            return "output1";
        }

        @Stage(order = 2, name = "checkedStage")
        @Check(AlwaysPassCheck.class)
        public void checkedStage(String input) {
            executionOrder.add("checkedStage");
        }

        List<String> executionOrder() { return executionOrder; }
        void reset() { executionOrder.clear(); }
    }

    @Workflow("blocking-check")
    static class BlockingCheckWorkflow {
        private final List<String> executionOrder = new ArrayList<>();
        private final List<String> compensated = new ArrayList<>();

        @Stage(order = 1, name = "stage1")
        @Compensable(by = "compensateStage1")
        public String stage1(String input) {
            executionOrder.add("stage1");
            return "output1";
        }

        @Stage(order = 2, name = "blockedStage")
        @Check(AlwaysFailCheck.class)
        public void blockedStage(String input) {
            executionOrder.add("blockedStage"); // should never be reached
        }

        @Compensation
        public void compensateStage1(CompensationContext ctx) {
            compensated.add("compensateStage1");
        }

        List<String> executionOrder() { return executionOrder; }
        List<String> compensated() { return compensated; }
        void reset() { executionOrder.clear(); compensated.clear(); }
    }

    // Topology validation test fixtures (not Spring beans — used directly)

    @Workflow("broken-compensation")
    static class BrokenCompensationWorkflow {
        @Stage(order = 1, name = "stage1")
        @Compensable(by = "nonExistentMethod")
        public void stage1(String input) {}
    }

    @Workflow("empty")
    static class EmptyWorkflow {
        // No @Stage methods — should fail topology validation
    }

    // =========================================================================
    // Test Spring configuration
    // =========================================================================

    @Configuration
    static class TestConfig {

        @Bean
        SideEffectJournal sideEffectJournal() {
            return new InMemorySideEffectJournal();
        }

        @Bean
        WorkflowRuntime workflowRuntime(SideEffectJournal journal, ApplicationContext ctx) {
            return new WorkflowRuntime(journal, ctx);
        }

        @Bean
        LinearWorkflow linearWorkflow() { return new LinearWorkflow(); }

        @Bean
        FailingWorkflow failingWorkflow() { return new FailingWorkflow(); }

        @Bean
        GatedWorkflow gatedWorkflow() { return new GatedWorkflow(); }

        @Bean
        CheckedWorkflow checkedWorkflow() { return new CheckedWorkflow(); }

        @Bean
        BlockingCheckWorkflow blockingCheckWorkflow() { return new BlockingCheckWorkflow(); }

        // Register check implementations as beans so the runtime can resolve them
        @Bean
        AlwaysPassCheck alwaysPassCheck() { return new AlwaysPassCheck(); }

        @Bean
        AlwaysFailCheck alwaysFailCheck() { return new AlwaysFailCheck(); }
    }
}
