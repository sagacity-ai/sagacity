package dev.sagacity.core.journal;

import java.util.List;

/**
 * Append-only log of everything a saga did. Implementations must preserve
 * per-saga append order. The Postgres implementation (M1) adds hash chaining
 * for tamper evidence.
 */
public interface SideEffectJournal {

	JournalEntry append(String sagaId, String toolName, Phase phase, String input, String payload);

	/** All entries for the saga, in append order. */
	List<JournalEntry> entries(String sagaId);

}
