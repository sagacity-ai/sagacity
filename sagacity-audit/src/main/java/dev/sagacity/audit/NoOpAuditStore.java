package dev.sagacity.audit;

import java.time.Instant;
import java.util.List;

/**
 * {@link AuditStore} that discards all entries.
 *
 * <p>Intended for benchmarks and tests that need a Sagacity bean wired but
 * do not want journal I/O to affect measurements or test state.
 *
 * <p><strong>Never use in production.</strong> Discarding journal entries
 * means compensation and tamper detection have no data to work with.
 */
public final class NoOpAuditStore implements AuditStore {

    /** Singleton instance — this store has no state. */
    public static final NoOpAuditStore INSTANCE = new NoOpAuditStore();

    @Override
    public AuditEntry append(String sagaId, String toolName, Phase phase, String input) {
        // Return a well-formed entry so callers that use the returned value don't NPE.
        return new AuditEntry(sagaId, 0L, toolName, phase, input, Instant.EPOCH, "");
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId) {
        return List.of();
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId, Class<? extends Phase> phaseType) {
        return List.of();
    }
}
