package dev.sagacity.core.compensation;

import java.util.ArrayList;
import java.util.List;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.journal.AuditEntry;
import dev.sagacity.core.journal.InMemoryAuditStore;
import dev.sagacity.core.journal.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationRunnerTest {

    private final InMemoryAuditStore store = new InMemoryAuditStore();
    private final CompensationRegistry registry = new CompensationRegistry();
    private final CompensationRunner runner = new CompensationRunner(store, registry);
    private final List<String> compensationOrder = new ArrayList<>();

    private void executed(String sagaId, String toolName) {
        store.append(sagaId, toolName, new Phase.Intent(), "{}");
        store.append(sagaId, toolName, new Phase.Executed("result-" + toolName), "{}");
    }

    private void register(String toolName, CompensationHandler handler) {
        registry.register(new CompensationRegistry.Registration(toolName, Reversibility.COMPENSATABLE, handler));
    }

    @Test
    @DisplayName("compensates in reverse order of execution")
    void compensatesInReverseOrder() {
        executed("s1", "a");
        executed("s1", "b");
        executed("s1", "c");
        register("a", ctx -> compensationOrder.add("undo-a"));
        register("b", ctx -> compensationOrder.add("undo-b"));
        register("c", ctx -> compensationOrder.add("undo-c"));

        CompensationReport report = runner.compensate("s1");

        assertThat(compensationOrder).containsExactly("undo-c", "undo-b", "undo-a");
        assertThat(report.allSucceeded()).isTrue();
        assertThat(store.findBySagaId("s1", Phase.Compensated.class)).hasSize(3);
    }

    @Test
    @DisplayName("failing compensation is recorded and run continues for remaining tools")
    void failingCompensationIsRecordedAndRunContinues() {
        executed("s2", "a");
        executed("s2", "b");
        register("a", ctx -> compensationOrder.add("undo-a"));
        register("b", ctx -> { throw new IllegalStateException("undo rejected"); });

        CompensationReport report = runner.compensate("s2");

        assertThat(compensationOrder).containsExactly("undo-a");
        assertThat(report.allSucceeded()).isFalse();
        assertThat(report.outcomes()).extracting(CompensationReport.Outcome::result)
                .containsExactly(CompensationReport.Result.COMPENSATION_FAILED,
                        CompensationReport.Result.COMPENSATED);
        assertThat(store.findBySagaId("s2", Phase.CompensationFailed.class)).hasSize(1);
    }

    @Test
    @DisplayName("tools without declared compensation are skipped and reported")
    void toolsWithoutCompensationAreSkipped() {
        executed("s3", "a");

        CompensationReport report = runner.compensate("s3");

        assertThat(report.outcomes()).extracting(CompensationReport.Outcome::result)
                .containsExactly(CompensationReport.Result.SKIPPED);
        assertThat(store.findBySagaId("s3", Phase.Compensated.class)).isEmpty();
    }

    @Test
    @DisplayName("failed tools are not compensated (only EXECUTED steps are)")
    void failedToolsAreNotCompensated() {
        store.append("s4", "a", new Phase.Intent(), "{}");
        store.append("s4", "a", new Phase.Failed("boom"), "{}");
        register("a", ctx -> compensationOrder.add("undo-a"));

        CompensationReport report = runner.compensate("s4");

        assertThat(compensationOrder).isEmpty();
        assertThat(report.outcomes()).isEmpty();
    }

    @Test
    @DisplayName("compensation context carries the original input and result")
    void compensationContextCarriesInputAndResult() {
        executed("s5", "a");
        List<CompensationContext> seen = new ArrayList<>();
        register("a", seen::add);

        runner.compensate("s5");

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).result()).isEqualTo("result-a");
        assertThat(seen.get(0).sagaId()).isEqualTo("s5");
    }

    @Test
    @DisplayName("uses phase-filtered query — only Executed entries are considered")
    void usesPhaseFilteredQuery() {
        // Mix of phases in one saga
        store.append("s6", "tool", new Phase.Intent(), "{}");
        store.append("s6", "tool", new Phase.Executed("result"), "{}");
        store.append("s6", "tool", new Phase.AwaitingApproval(), "{}");
        register("tool", ctx -> compensationOrder.add("undo-tool"));

        runner.compensate("s6");

        // Only the Executed entry should trigger compensation — once
        assertThat(compensationOrder).containsExactly("undo-tool");
    }
}
