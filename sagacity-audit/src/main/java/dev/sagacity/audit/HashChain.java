package dev.sagacity.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes and verifies SHA-256 hash chains over audit entries.
 *
 * <p>Each entry's hash is a SHA-256 digest over the length-prefixed concatenation
 * of: previousHash, sagaId, seq, toolName, phase discriminator, phase data JSON,
 * input, and timestamp. The first entry in a saga uses {@link #zeroHash()} as
 * the previous hash.
 *
 * <h2>Why length prefixes</h2>
 * <p>Tamper evidence only holds if each entry maps to exactly one preimage.
 * Joining fields with a delimiter allows collisions when fields contain the
 * delimiter. Length-prefixing every field ({@code "<byte-length>:<utf8-bytes>"})
 * makes the encoding unambiguous — no choice of field values can produce the
 * same digest as a different set of field values.
 *
 * <h2>Timestamp precision</h2>
 * <p>Timestamps are canonicalized to microseconds before hashing, matching
 * the precision of JDBC {@code TIMESTAMP} columns. A nanosecond-precision
 * {@link Instant} would hash one value and read back another, breaking
 * chain verification on every persisted saga.
 */
public final class HashChain {

    private static final String ZERO_HASH = "0".repeat(64);

    private HashChain() {}

    /**
     * Computes the hash for one audit entry.
     *
     * @param previousHash hash of the preceding entry, or {@link #zeroHash()} for the first
     * @param entry        the entry being appended (seq and timestamp must already be set)
     * @return 64-character lowercase hex SHA-256 digest
     */
    public static String computeHash(String previousHash, AuditEntry entry) {
        return computeHash(previousHash, entry.sagaId(), entry.seq(), entry.toolName(),
                entry.phase(), entry.input(), entry.timestamp());
    }

    /**
     * Computes the hash from raw field values — used during append before the
     * {@link AuditEntry} record is constructed.
     */
    public static String computeHash(String previousHash, String sagaId, long seq,
            String toolName, Phase phase, String input, Instant timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateField(digest, previousHash);
            updateField(digest, sagaId);
            updateField(digest, Long.toString(seq));
            updateField(digest, toolName);
            updateField(digest, phase.discriminator());
            updateField(digest, phase.toJson());
            updateField(digest, input);
            updateField(digest, canonicalTimestamp(timestamp));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Computes a standalone SHA-256 hash of a single input string.
     * Used to bind an approval to the exact payload it was granted for,
     * preventing stale approvals from executing against a different payload.
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Verifies the hash chain integrity for a list of entries.
     *
     * @param entries entries in ascending sequence order
     * @return {@code true} if every entry's hash is consistent with the chain;
     *         {@code false} if any entry has been tampered with
     */
    public static boolean verify(List<AuditEntry> entries) {
        if (entries.isEmpty()) return true;
        String previousHash = ZERO_HASH;
        for (AuditEntry entry : entries) {
            String expected = computeHash(previousHash, entry);
            if (!expected.equals(entry.hash())) {
                return false;
            }
            previousHash = entry.hash();
        }
        return true;
    }

    /** Returns the canonical zero hash used for the first entry in every saga. */
    public static String zeroHash() {
        return ZERO_HASH;
    }

    /** Feeds one field as {@code <utf8-byte-length>:<utf8-bytes>}. */
    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
    }

    /**
     * Fixed-width epoch-second and microsecond rendering. Avoids
     * {@link Instant#toString()}, whose output width varies with trailing-zero
     * suppression, and pins precision to what the journal's storage can hold.
     */
    static String canonicalTimestamp(Instant timestamp) {
        Instant micros = timestamp.truncatedTo(ChronoUnit.MICROS);
        return micros.getEpochSecond() + "." + String.format("%06d", micros.getNano() / 1_000);
    }
}
