package dev.sagacity.core.journal;

import java.util.List;

/**
 * Pluggable storage backend for Sagacity's tamper-evident audit trail.
 *
 * <p>Implementations must be append-only and preserve per-saga entry order.
 * The store is the foundation of the three governance pillars:
 * <ul>
 *   <li><strong>Visibility</strong> — every tool call is recorded here
 *   <li><strong>Control</strong> — approval gates write AWAITING_APPROVAL entries
 *   <li><strong>Recovery</strong> — compensation runner reads EXECUTED entries in reverse
 * </ul>
 *
 * <h2>Built-in implementations</h2>
 * <ul>
 *   <li>{@link JdbcAuditStore} — durable, hash-chained, works on any JDBC database
 *   <li>{@link InMemoryAuditStore} — in-process, no persistence (dev/test only)
 *   <li>{@link Slf4jAuditStore} — logs entries via SLF4J, zero infrastructure
 *   <li>{@link NoOpAuditStore} — discards all entries (benchmarks/testing)
 *   <li>{@link CompositeAuditStore} — fans out to multiple stores simultaneously
 * </ul>
 *
 * <h2>Wiring multiple stores</h2>
 * <pre>{@code
 * AuditStore store = CompositeAuditStore.of(
 *     new JdbcAuditStore(dataSource),   // primary: durable + hash-chained
 *     new Slf4jAuditStore()             // secondary: human-readable log output
 * );
 * }</pre>
 */
public interface AuditStore {

    /**
     * Appends one entry to the audit trail.
     *
     * <p>The store assigns the {@code seq} number. The returned entry is the
     * fully populated record with the assigned seq, timestamp, and hash.
     * Implementations must be durable — the entry must be persisted before
     * this method returns.
     *
     * @param sagaId   the saga this entry belongs to
     * @param toolName the tool whose lifecycle phase is being recorded
     * @param phase    the phase variant, carrying phase-specific data
     * @param input    snapshot of the tool's input at recording time
     * @return the persisted entry with assigned seq, timestamp, and hash
     */
    AuditEntry append(String sagaId, String toolName, Phase phase, String input);

    /**
     * Returns all entries for a saga in ascending sequence order.
     *
     * @param sagaId the saga to query
     * @return entries in append order, empty list if the saga has no entries
     */
    List<AuditEntry> findBySagaId(String sagaId);

    /**
     * Returns entries for a saga filtered by phase type, in ascending sequence order.
     *
     * <p>This is the efficient query path for the compensation runner (needs only
     * {@link Phase.Executed} entries) and the approval controller (needs only
     * {@link Phase.AwaitingApproval} entries). Implementations backed by a
     * database should push this filter to the query rather than filtering in memory.
     *
     * @param sagaId    the saga to query
     * @param phaseType the phase class to filter by (e.g. {@code Phase.Executed.class})
     * @return matching entries in append order
     */
    List<AuditEntry> findBySagaId(String sagaId, Class<? extends Phase> phaseType);
}
