package dev.sagacity.core.compensation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.sagacity.core.journal.JournalEntry;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;

/**
 * Walks a saga's EXECUTED effects in reverse order and runs their declared
 * compensations, journaling each outcome. A failing compensation is recorded
 * and the run continues — partial cleanup beats none, and the journal keeps
 * the evidence of what remains dirty.
 */
public final class CompensationRunner {

	private final SideEffectJournal journal;

	private final CompensationRegistry registry;

	public CompensationRunner(SideEffectJournal journal, CompensationRegistry registry) {
		this.journal = journal;
		this.registry = registry;
	}

	public CompensationReport compensate(String sagaId) {
		List<JournalEntry> executed = this.journal.entries(sagaId)
			.stream()
			.filter(entry -> entry.phase() == Phase.EXECUTED)
			.sorted(Comparator.comparingLong(JournalEntry::seq).reversed())
			.toList();

		List<CompensationReport.Outcome> outcomes = new ArrayList<>();
		for (JournalEntry effect : executed) {
			outcomes.add(compensateOne(sagaId, effect));
		}
		return new CompensationReport(sagaId, List.copyOf(outcomes));
	}

	private CompensationReport.Outcome compensateOne(String sagaId, JournalEntry effect) {
		CompensationRegistry.Registration registration = this.registry.find(effect.toolName());
		if (registration == null || registration.handler() == null) {
			return new CompensationReport.Outcome(effect.toolName(), effect.seq(), CompensationReport.Result.SKIPPED,
					"no compensation declared");
		}
		CompensationContext context = new CompensationContext(sagaId, effect.toolName(), effect.input(),
				effect.payload());
		try {
			registration.handler().compensate(context);
			this.journal.append(sagaId, effect.toolName(), Phase.COMPENSATED, effect.input(),
					"compensated effect seq=" + effect.seq());
			return new CompensationReport.Outcome(effect.toolName(), effect.seq(),
					CompensationReport.Result.COMPENSATED, "");
		}
		catch (Exception ex) {
			String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			this.journal.append(sagaId, effect.toolName(), Phase.COMPENSATION_FAILED, effect.input(), detail);
			return new CompensationReport.Outcome(effect.toolName(), effect.seq(),
					CompensationReport.Result.COMPENSATION_FAILED, detail);
		}
	}

}
