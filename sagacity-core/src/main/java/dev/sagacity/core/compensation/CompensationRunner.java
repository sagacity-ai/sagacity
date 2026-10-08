package dev.sagacity.core.compensation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.sagacity.core.journal.AuditEntry;
import dev.sagacity.core.journal.AuditStore;
import dev.sagacity.core.journal.Phase;

/**
 * Walks a saga's {@link Phase.Executed} effects in reverse order and runs their
 * declared compensations, journaling each outcome.
 *
 * <p>A failing compensation is recorded as {@link Phase.CompensationFailed} and
 * the run continues — partial cleanup beats none, and the audit trail keeps
 * evidence of what remains dirty.
 *
 * <p><strong>Not idempotent.</strong> Calling {@code compensate(sagaId)} twice
 * issues two compensation attempts per tool. The caller is responsible for
 * ensuring this is called exactly once per failed saga.
 */
public final class CompensationRunner {

    private final AuditStore auditStore;

    private final CompensationRegistry registry;

    public CompensationRunner(AuditStore auditStore, CompensationRegistry registry) {
        this.auditStore = auditStore;
        this.registry = registry;
    }

    /**
     * Runs compensation for all {@link Phase.Executed} effects in the saga,
     * in reverse order.
     *
     * @param sagaId the saga to compensate
     * @return a report of each compensation outcome
     */
    public CompensationReport compensate(String sagaId) {
        // Use the efficient phase-filtered query — only EXECUTED entries need compensation
        List<AuditEntry> executed = auditStore.findBySagaId(sagaId, Phase.Executed.class)
                .stream()
                .sorted(Comparator.comparingLong(AuditEntry::seq).reversed())
                .toList();

        List<CompensationReport.Outcome> outcomes = new ArrayList<>();
        for (AuditEntry effect : executed) {
            outcomes.add(compensateOne(sagaId, effect));
        }
        return new CompensationReport(sagaId, List.copyOf(outcomes));
    }

    private CompensationReport.Outcome compensateOne(String sagaId, AuditEntry effect) {
        CompensationRegistry.Registration registration = registry.find(effect.toolName());
        if (registration == null || registration.handler() == null) {
            return new CompensationReport.Outcome(effect.toolName(), effect.seq(),
                    CompensationReport.Result.SKIPPED, "no compensation declared");
        }

        // Extract the result from the Executed phase using pattern matching
        String result = (effect.phase() instanceof Phase.Executed e) ? e.result() : "";
        CompensationContext context = new CompensationContext(
                sagaId, effect.toolName(), effect.input(), result);

        try {
            registration.handler().compensate(context);
            auditStore.append(sagaId, effect.toolName(), new Phase.Compensated(), effect.input());
            return new CompensationReport.Outcome(effect.toolName(), effect.seq(),
                    CompensationReport.Result.COMPENSATED, "");
        } catch (Exception ex) {
            String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            auditStore.append(sagaId, effect.toolName(), new Phase.CompensationFailed(detail), effect.input());
            return new CompensationReport.Outcome(effect.toolName(), effect.seq(),
                    CompensationReport.Result.COMPENSATION_FAILED, detail);
        }
    }
}
