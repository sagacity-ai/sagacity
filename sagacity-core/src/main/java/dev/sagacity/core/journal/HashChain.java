package dev.sagacity.core.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes and verifies SHA-256 hash chains over journal entries.
 * Each entry's hash = SHA-256(previousHash || sagaId || seq || toolName || phase || input || payload || timestamp).
 * The first entry in a saga uses a zero-hash as the previous.
 */
public final class HashChain {

	private static final String ZERO_HASH = "0".repeat(64);

	private HashChain() {}

	public static String computeHash(String previousHash, String sagaId, long seq, String toolName,
			Phase phase, String input, String payload, Instant timestamp) {
		String content = previousHash + "|" + sagaId + "|" + seq + "|" + toolName + "|" + phase.name()
				+ "|" + input + "|" + payload + "|" + timestamp.toString();
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	public static String zeroHash() {
		return ZERO_HASH;
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
