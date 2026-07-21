package dev.sagacity.core.approval;

import java.util.List;

/**
 * Stores and retrieves pending approval requests.
 */
public interface ApprovalStore {

	void save(ApprovalRequest request);

	List<ApprovalRequest> pendingRequests();

	List<ApprovalRequest> pendingRequests(String sagaId);

	void remove(String sagaId, long journalSeq);

}
