package dev.sagacity.core.approval;

import java.time.Instant;

/**
 * Records the decision made by a human approver.
 *
 * @param sagaId the saga
 * @param journalSeq the journal entry that was approved/rejected
 * @param approved true if approved, false if rejected
 * @param approverIdentity who made the decision (e.g. email, username)
 * @param decidedAt when the decision was made
 */
public record ApprovalDecision(String sagaId, long journalSeq, boolean approved,
		String approverIdentity, Instant decidedAt) {
}
