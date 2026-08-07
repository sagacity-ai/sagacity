package dev.sagacity.core.approval;

/**
 * Represents a pending approval request for an IRREVERSIBLE tool.
 *
 * <p>The {@code inputHash} field is a SHA-256 digest of the exact {@code input}
 * payload at the moment the approval was requested. When the tool is eventually
 * executed after approval, Sagacity recomputes the hash of the live payload and
 * compares it to this value. If they differ (stale approval — the model re-planned
 * and changed the payload), execution is rejected even though an approval exists.
 *
 * @param sagaId     the saga that is suspended
 * @param journalSeq the journal entry seq that needs approval
 * @param toolName   the tool waiting for approval
 * @param input      the exact tool input JSON that would be executed
 * @param inputHash  SHA-256 of {@code input} — binds approval to this payload
 */
public record ApprovalRequest(String sagaId, long journalSeq, String toolName,
		String input, String inputHash) {

	/**
	 * Backward-compatible constructor without inputHash (defaults to empty string).
	 * Preserved so existing callers not using payload binding still compile.
	 * Prefer the full constructor for any new code.
	 */
	public ApprovalRequest(String sagaId, long journalSeq, String toolName, String input) {
		this(sagaId, journalSeq, toolName, input, "");
	}

}
