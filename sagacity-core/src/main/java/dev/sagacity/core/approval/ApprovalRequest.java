package dev.sagacity.core.approval;

/**
 * Represents a pending approval request for an IRREVERSIBLE tool.
 *
 * @param sagaId the saga that is suspended
 * @param journalSeq the journal entry seq that needs approval
 * @param toolName the tool waiting for approval
 * @param input the tool input that would be executed
 */
public record ApprovalRequest(String sagaId, long journalSeq, String toolName, String input) {
}
