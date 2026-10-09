package dev.sagacity.audit;

import java.time.Instant;

/**
 * One immutable entry in a saga's tamper-evident audit trail.
 *
 * <p>Each entry describes one phase transition for one tool call within a saga.
 * The {@link Phase} variant carries the phase-specific data — no generic
 * {@code payload} field with overloaded meaning.
 *
 * <p>Entries are hash-chained: each entry's {@code hash} is a SHA-256 digest
 * over the previous entry's hash plus all fields of this entry. Any
 * post-hoc modification to any field invalidates every subsequent hash in
 * the chain, making tampering detectable.
 *
 * @param sagaId    unique identifier for the saga this entry belongs to
 * @param seq       strictly increasing sequence number within the saga,
 *                  assigned by the {@link AuditStore} implementation
 * @param toolName  name of the tool whose lifecycle this entry records
 * @param phase     the lifecycle phase, carrying phase-specific typed data
 * @param input     snapshot of the tool's input JSON at the time of recording
 * @param timestamp UTC instant the entry was appended, truncated to microseconds
 * @param hash      SHA-256 hash chaining this entry to its predecessor,
 *                  or {@link HashChain#zeroHash()} for the first entry in a saga
 */
public record AuditEntry(
        String sagaId,
        long seq,
        String toolName,
        Phase phase,
        String input,
        Instant timestamp,
        String hash) {

    /**
     * Convenience constructor without hash — used by {@link InMemoryAuditStore}
     * which does not maintain a hash chain.
     */
    public AuditEntry(String sagaId, long seq, String toolName,
                      Phase phase, String input, Instant timestamp) {
        this(sagaId, seq, toolName, phase, input, timestamp, "");
    }
}
