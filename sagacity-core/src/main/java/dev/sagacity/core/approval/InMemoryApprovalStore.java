package dev.sagacity.core.approval;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory approval store for tests and demos.
 */
public final class InMemoryApprovalStore implements ApprovalStore {

	private final CopyOnWriteArrayList<ApprovalRequest> requests = new CopyOnWriteArrayList<>();

	@Override
	public void save(ApprovalRequest request) {
		this.requests.add(request);
	}

	@Override
	public List<ApprovalRequest> pendingRequests() {
		return List.copyOf(this.requests);
	}

	@Override
	public List<ApprovalRequest> pendingRequests(String sagaId) {
		return this.requests.stream().filter(r -> r.sagaId().equals(sagaId)).toList();
	}

	@Override
	public Optional<ApprovalRequest> find(String sagaId, long journalSeq) {
		return this.requests.stream()
				.filter(r -> r.sagaId().equals(sagaId) && r.journalSeq() == journalSeq)
				.findFirst();
	}

	@Override
	public void remove(String sagaId, long journalSeq) {
		this.requests.removeIf(r -> r.sagaId().equals(sagaId) && r.journalSeq() == journalSeq);
	}

}
