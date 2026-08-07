package dev.sagacity.core.approval;

import java.util.List;
import java.util.Optional;

/**
 * Stores and retrieves pending approval requests.
 */
public interface ApprovalStore {

	void save(ApprovalRequest request);

	List<ApprovalRequest> pendingRequests();

	List<ApprovalRequest> pendingRequests(String sagaId);

	/**
	 * Look up a specific pending request by saga and journal sequence.
	 * Used during payload hash verification before resuming an approved tool.
	 */
	Optional<ApprovalRequest> find(String sagaId, long journalSeq);

	void remove(String sagaId, long journalSeq);

}
