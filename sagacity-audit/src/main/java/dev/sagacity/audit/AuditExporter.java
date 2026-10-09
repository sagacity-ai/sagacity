package dev.sagacity.audit;

import java.util.List;
import java.util.stream.Collectors;

import dev.sagacity.audit.AuditEntry;
import dev.sagacity.audit.AuditStore;
import dev.sagacity.audit.HashChain;

/**
 * Exports audit entries as JSON Lines for compliance reporting, and verifies
 * the hash chain for tamper detection.
 *
 * <p>Each JSON Lines export line is a self-contained JSON object covering all
 * fields of one {@link AuditEntry}. The {@code phase} and {@code phaseData}
 * fields together carry the full phase information — {@code phase} is the
 * discriminator (e.g. {@code "Executed"}) and {@code phaseData} is the
 * phase-specific JSON (e.g. {@code {"result":"ok"}}).
 */
public final class AuditExporter {

    private final AuditStore auditStore;

    public AuditExporter(AuditStore auditStore) {
        this.auditStore = auditStore;
    }

    /**
     * Exports all entries for a saga as JSON Lines.
     *
     * <p>Each line:
     * <pre>
     * {"sagaId":"...","seq":1,"toolName":"...","phase":"Executed",
     *  "phaseData":{"result":"ok"},"input":"...","timestamp":"...","hash":"..."}
     * </pre>
     */
    public String exportJsonLines(String sagaId) {
        List<AuditEntry> entries = this.auditStore.findBySagaId(sagaId);
        return entries.stream().map(this::toJsonLine).collect(Collectors.joining("\n"));
    }

    /**
     * Verifies the hash chain integrity for a saga.
     *
     * @return a {@link VerificationResult} indicating pass/fail and break point
     */
    public VerificationResult verify(String sagaId) {
        List<AuditEntry> entries = this.auditStore.findBySagaId(sagaId);
        if (entries.isEmpty()) {
            return new VerificationResult(true, 0, -1, "empty journal");
        }
        if (entries.stream().allMatch(e -> e.hash().isEmpty())) {
            return new VerificationResult(false, entries.size(), -1,
                    "journal is not hash-chained — tamper evidence unavailable "
                    + "(use JdbcAuditStore for hash-chained storage)");
        }
        String previousHash = HashChain.zeroHash();
        for (int i = 0; i < entries.size(); i++) {
            AuditEntry entry = entries.get(i);
            String expected = HashChain.computeHash(previousHash, entry);
            if (!expected.equals(entry.hash())) {
                return new VerificationResult(false, entries.size(), i,
                        "hash mismatch at seq=" + entry.seq() + " (entry " + i + ")");
            }
            previousHash = entry.hash();
        }
        return new VerificationResult(true, entries.size(), -1,
                "all " + entries.size() + " entries verified");
    }

    private String toJsonLine(AuditEntry entry) {
        return "{"
                + "\"sagaId\":\"" + escape(entry.sagaId()) + "\","
                + "\"seq\":" + entry.seq() + ","
                + "\"toolName\":\"" + escape(entry.toolName()) + "\","
                + "\"phase\":\"" + entry.phase().discriminator() + "\","
                + "\"phaseData\":" + entry.phase().toJson() + ","
                + "\"input\":\"" + escape(entry.input()) + "\","
                + "\"timestamp\":\"" + entry.timestamp().toString() + "\","
                + "\"hash\":\"" + entry.hash() + "\""
                + "}";
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * Result of verifying a journal's hash chain.
     *
     * @param valid        true if the chain is intact
     * @param entryCount   total entries checked
     * @param breakAtIndex index where the break was detected (-1 if valid or empty)
     * @param message      human-readable summary
     */
    public record VerificationResult(boolean valid, int entryCount, int breakAtIndex, String message) {}
}
