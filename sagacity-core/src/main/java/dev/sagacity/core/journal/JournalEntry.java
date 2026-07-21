package dev.sagacity.core.journal;

import java.time.Instant;

/**
 * One immutable row in a saga's side-effect journal.
 *
 * @param sagaId the saga this entry belongs to
 * @param seq strictly increasing sequence within the saga (assigned by the journal)
 * @param toolName the tool whose effect this entry records
 * @param phase lifecycle phase recorded by this entry
 * @param input snapshot of the tool input (JSON as passed to the tool)
 * @param payload result snapshot (EXECUTED), error (FAILED/COMPENSATION_FAILED), or ""
 * @param timestamp UTC time the entry was appended
 */
public record JournalEntry(String sagaId, long seq, String toolName, Phase phase, String input, String payload,
		Instant timestamp) {

}
