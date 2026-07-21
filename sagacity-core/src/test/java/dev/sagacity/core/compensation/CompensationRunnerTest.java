package dev.sagacity.core.compensation;

import java.util.ArrayList;
import java.util.List;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.journal.InMemorySideEffectJournal;
import dev.sagacity.core.journal.Phase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationRunnerTest {

	private final InMemorySideEffectJournal journal = new InMemorySideEffectJournal();

	private final CompensationRegistry registry = new CompensationRegistry();

	private final CompensationRunner runner = new CompensationRunner(this.journal, this.registry);

	private final List<String> compensationOrder = new ArrayList<>();

	private void executed(String sagaId, String toolName) {
		this.journal.append(sagaId, toolName, Phase.INTENT, "{}", "");
		this.journal.append(sagaId, toolName, Phase.EXECUTED, "{}", "result-" + toolName);
	}

	private void register(String toolName, CompensationHandler handler) {
		this.registry.register(new CompensationRegistry.Registration(toolName, Reversibility.COMPENSATABLE, handler));
	}

	@Test
	void compensatesInReverseOrderOfExecution() {
		executed("s1", "a");
		executed("s1", "b");
		executed("s1", "c");
		register("a", ctx -> this.compensationOrder.add("undo-a"));
		register("b", ctx -> this.compensationOrder.add("undo-b"));
		register("c", ctx -> this.compensationOrder.add("undo-c"));

		CompensationReport report = this.runner.compensate("s1");

		assertThat(this.compensationOrder).containsExactly("undo-c", "undo-b", "undo-a");
		assertThat(report.allSucceeded()).isTrue();
		assertThat(this.journal.entries("s1")).filteredOn(e -> e.phase() == Phase.COMPENSATED).hasSize(3);
	}

	@Test
	void failingCompensationIsRecordedAndRunContinues() {
		executed("s2", "a");
		executed("s2", "b");
		register("a", ctx -> this.compensationOrder.add("undo-a"));
		register("b", ctx -> {
			throw new IllegalStateException("undo rejected");
		});

		CompensationReport report = this.runner.compensate("s2");

		assertThat(this.compensationOrder).containsExactly("undo-a");
		assertThat(report.allSucceeded()).isFalse();
		assertThat(report.outcomes()).extracting(CompensationReport.Outcome::result)
			.containsExactly(CompensationReport.Result.COMPENSATION_FAILED, CompensationReport.Result.COMPENSATED);
		assertThat(this.journal.entries("s2")).filteredOn(e -> e.phase() == Phase.COMPENSATION_FAILED)
			.hasSize(1)
			.first()
			.satisfies(e -> assertThat(e.payload()).isEqualTo("undo rejected"));
	}

	@Test
	void toolsWithoutDeclaredCompensationAreSkippedButReported() {
		executed("s3", "a");

		CompensationReport report = this.runner.compensate("s3");

		assertThat(report.outcomes()).extracting(CompensationReport.Outcome::result)
			.containsExactly(CompensationReport.Result.SKIPPED);
		assertThat(this.journal.entries("s3")).filteredOn(e -> e.phase() == Phase.COMPENSATED).isEmpty();
	}

	@Test
	void failedToolsAreNotCompensated() {
		this.journal.append("s4", "a", Phase.INTENT, "{}", "");
		this.journal.append("s4", "a", Phase.FAILED, "{}", "boom");
		register("a", ctx -> this.compensationOrder.add("undo-a"));

		CompensationReport report = this.runner.compensate("s4");

		assertThat(this.compensationOrder).isEmpty();
		assertThat(report.outcomes()).isEmpty();
	}

	@Test
	void compensationContextCarriesOriginalInputAndResult() {
		executed("s5", "a");
		List<CompensationContext> seen = new ArrayList<>();
		register("a", seen::add);

		this.runner.compensate("s5");

		assertThat(seen).hasSize(1);
		assertThat(seen.get(0).result()).isEqualTo("result-a");
		assertThat(seen.get(0).sagaId()).isEqualTo("s5");
	}

}
