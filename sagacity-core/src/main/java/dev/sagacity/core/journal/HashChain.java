package dev.sagacity.core.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes and verifies SHA-256 hash chains over journal entries.
 * Each entry's hash = SHA-256 over the length-prefixed concatenation of
 * previousHash, sagaId, seq, toolName, phase, input, payload and timestamp.
 * The first entry in a saga uses a zero-hash as the previous.
 *
 * <h3>Why length prefixes</h3>
 * <p>Tamper evidence only holds if one entry maps to exactly one preimage.
 * Joining fields with a delimiter does not give that when the fields are tool
 * arguments and results, which can contain any character including the
 * delimiter: with a plain {@code "|"} join, {@code input="a|b", payload="c"}
 * and {@code input="a", payload="b|c"} produce identical content. Prefixing
 * every field with its UTF-8 byte length makes the encoding unambiguous, so no
 * choice of field contents can forge another entry's hash.
 *
 * <h3>Timestamp precision</h3>
 * <p>Timestamps are canonicalized to microseconds before hashing. Postgres
 * {@code TIMESTAMP} stores microseconds, so a nanosecond-precision
 * {@link Instant#now()} would hash one value and read back another, breaking
 * verification for every persisted saga on platforms whose clock has
 * sub-microsecond resolution.
 */
public final class HashChain {

	private static final String ZERO_HASH = "0".repeat(64);

	private HashChain() {}

	public static String computeHash(String previousHash, String sagaId, long seq, String toolName,
			Phase phase, String input, String payload, Instant timestamp) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateField(digest, previousHash);
			updateField(digest, sagaId);
			updateField(digest, Long.toString(seq));
			updateField(digest, toolName);
			updateField(digest, phase.name());
			updateField(digest, input);
			updateField(digest, payload);
			updateField(digest, canonicalTimestamp(timestamp));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
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

	public static String zeroHash() {
		return ZERO_HASH;
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
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	/**
	 * Verifies the integrity of a journal entry chain.
	 * Returns true if all hashes are valid, false if tampered.
	 */
	public static boolean verify(List<JournalEntry> entries) {
		if (entries.isEmpty()) return true;
		String previousHash = ZERO_HASH;
		for (JournalEntry entry : entries) {
			String expected = computeHash(previousHash, entry.sagaId(), entry.seq(), entry.toolName(),
					entry.phase(), entry.input(), entry.payload(), entry.timestamp());
			if (!expected.equals(entry.hash())) {
				return false;
			}
			previousHash = entry.hash();
		}
		return true;
	}
}
