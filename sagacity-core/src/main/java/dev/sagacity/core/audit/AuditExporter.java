package dev.sagacity.core.audit;

import java.util.List;
import java.util.stream.Collectors;

import dev.sagacity.core.journal.HashChain;
import dev.sagacity.core.journal.JournalEntry;
import dev.sagacity.core.journal.SideEffectJournal;

/**
 * Exports journal entries as JSON Lines format for compliance and audit purposes.
 * Each line is a self-contained JSON object. The full export can be verified
 * using {@link HashChain#verify(List)}.
 */
public final class AuditExporter {

	private final SideEffectJournal journal;

	public AuditExporter(SideEffectJournal journal) {
		this.journal = journal;
	}

	/**
	 * Export all entries for a saga as JSON Lines. Each line:
	 * {"sagaId":"...","seq":1,"toolName":"...","phase":"...","input":"...","payload":"...","timestamp":"...","hash":"..."}
	 */
	public String exportJsonLines(String sagaId) {
		List<JournalEntry> entries = this.journal.entries(sagaId);
		return entries.stream().map(this::toJsonLine).collect(Collectors.joining("\n"));
	}

	/**
	 * Verify the integrity of a saga's journal chain.
	 * @return a VerificationResult indicating pass/fail and the break point if
	 * tampered
	 */
	public VerificationResult verify(String sagaId) {
		List<JournalEntry> entries = this.journal.entries(sagaId);
		if (entries.isEmpty()) {
			return new VerificationResult(true, entries.size(), -1, "empty journal");
		}
		String previousHash = HashChain.zeroHash();
		for (int i = 0; i < entries.size(); i++) {
			JournalEntry entry = entries.get(i);
			String expected = HashChain.computeHash(previousHash, entry.sagaId(), entry.seq(), entry.toolName(),
					entry.phase(), entry.input(), entry.payload(), entry.timestamp());
			if (!expected.equals(entry.hash())) {
				return new VerificationResult(false, entries.size(), i,
						"hash mismatch at seq=" + entry.seq() + " (entry " + i + ")");
			}
			previousHash = entry.hash();
		}
		return new VerificationResult(true, entries.size(), -1, "all " + entries.size() + " entries verified");
	}

	private String toJsonLine(JournalEntry entry) {
		// Manual JSON to avoid adding a Jackson dependency to sagacity-core
		return "{" + "\"sagaId\":\"" + escape(entry.sagaId()) + "\"," + "\"seq\":" + entry.seq() + ","
				+ "\"toolName\":\"" + escape(entry.toolName()) + "\"," + "\"phase\":\"" + entry.phase().name() + "\","
				+ "\"input\":\"" + escape(entry.input()) + "\"," + "\"payload\":\"" + escape(entry.payload()) + "\","
				+ "\"timestamp\":\"" + entry.timestamp().toString() + "\"," + "\"hash\":\"" + entry.hash() + "\"" + "}";
	}

	private String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	/**
	 * Result of verifying a journal's hash chain.
	 *
	 * @param valid true if the chain is intact
	 * @param entryCount total entries checked
	 * @param breakAtIndex index where the break was detected (-1 if valid)
	 * @param message human-readable summary
	 */
	public record VerificationResult(boolean valid, int entryCount, int breakAtIndex, String message) {
	}

}
