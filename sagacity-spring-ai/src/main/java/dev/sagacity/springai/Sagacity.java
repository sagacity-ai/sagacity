package dev.sagacity.springai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.approval.ApprovalDecision;
import dev.sagacity.core.approval.ApprovalRequest;
import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.approval.InMemoryApprovalStore;
import dev.sagacity.core.audit.AuditExporter;
import dev.sagacity.core.compensation.CompensationRegistry;
import dev.sagacity.core.compensation.CompensationReport;
import dev.sagacity.core.compensation.CompensationRunner;
import dev.sagacity.core.journal.HashChain;
import dev.sagacity.core.journal.InMemorySideEffectJournal;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Entry point. Wrap tool beans once, then run agent work inside saga scopes:
 *
 * <pre>{@code
 * Sagacity sagacity = Sagacity.create();
 * ToolCallback[] tools = sagacity.wrap(new RefundTools());
 *
 * SagaResult<ChatResponse> result = sagacity.saga("refund-order-123",
 *         () -> chatClient.prompt().user("...").toolCallbacks(tools).call().chatResponse());
 * }</pre>
 */
public final class Sagacity {

	private final SideEffectJournal journal;

	private final ApprovalStore approvalStore;

	private final CompensationRegistry registry = new CompensationRegistry();

	private final CompensationRunner runner;

	private final AuditExporter auditExporter;

	private Sagacity(SideEffectJournal journal, ApprovalStore approvalStore) {
		this.journal = journal;
		this.approvalStore = approvalStore;
		this.runner = new CompensationRunner(journal, this.registry);
		this.auditExporter = new AuditExporter(journal);
	}

	/** In-memory journal + approval store — tests and demos. */
	public static Sagacity create() {
		return new Sagacity(new InMemorySideEffectJournal(), new InMemoryApprovalStore());
	}

	public static Sagacity create(SideEffectJournal journal) {
		return new Sagacity(journal, new InMemoryApprovalStore());
	}

	public static Sagacity create(SideEffectJournal journal, ApprovalStore approvalStore) {
		return new Sagacity(journal, approvalStore);
	}

	/**
	 * Builds Spring AI tool callbacks from {@code @Tool}-annotated beans, registers
	 * their {@code @Compensable} declarations (failing fast on broken ones), and
	 * returns every callback wrapped with journaling + approval gates.
	 */
	public ToolCallback[] wrap(Object... toolBeans) {
		List<ToolCallback> wrapped = new ArrayList<>();
		for (Object toolBean : toolBeans) {
			CompensationScanner.scan(toolBean, this.registry);
			ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
				.toolObjects(toolBean)
				.build()
				.getToolCallbacks();
			for (ToolCallback callback : callbacks) {
				Reversibility rev = CompensationScanner.reversibilityFor(toolBean,
						callback.getToolDefinition().name(), this.registry);
				wrapped.add(new SagacityToolCallback(callback, this.journal, this.approvalStore, rev));
			}
		}
		return wrapped.toArray(ToolCallback[]::new);
	}

	/**
	 * Runs agent work in a saga scope. If the work throws, or any wrapped tool
	 * failed (even when the failure was swallowed and fed back to the model as an
	 * error message), compensations run in reverse order of execution. If an
	 * IRREVERSIBLE tool is encountered, the saga returns with status
	 * AWAITING_APPROVAL.
	 */
	public <T> SagaResult<T> saga(String sagaId, Supplier<T> work) {
		SagaScope.open(sagaId);
		try {
			T value;
			try {
				value = work.get();
			}
			catch (RuntimeException ex) {
				SagaScope.markFailed(ex);
				CompensationReport report = this.runner.compensate(sagaId);
				return SagaResult.compensated(sagaId, report, SagaScope.failure());
			}
			if (SagaScope.failure() != null) {
				CompensationReport report = this.runner.compensate(sagaId);
				return SagaResult.compensated(sagaId, report, SagaScope.failure());
			}
			if (SagaScope.isAwaitingApproval()) {
				return SagaResult.awaitingApproval(sagaId, SagaScope.awaitingToolName());
			}
			return SagaResult.completed(sagaId, value);
		}
		finally {
			SagaScope.close();
		}
	}

	public SagaResult<Void> saga(String sagaId, Runnable work) {
		return saga(sagaId, () -> {
			work.run();
			return null;
		});
	}

	/**
	 * Approve a pending IRREVERSIBLE tool execution. Journals the approval with the
	 * approver's identity. Does NOT remove the approval from the store — that
	 * happens in {@link #resumeSaga} after payload hash verification.
	 */
	public ApprovalDecision approve(String sagaId, long journalSeq, String approverIdentity) {
		this.journal.append(sagaId, "approval-gate", Phase.APPROVED, "seq=" + journalSeq,
				"approver=" + approverIdentity);
		// Intentionally NOT removing from store here — resumeSaga verifies the
		// payload hash and removes the request after successful verification.
		return new ApprovalDecision(sagaId, journalSeq, true, approverIdentity, Instant.now());
	}

	/**
	 * Reject a pending IRREVERSIBLE tool execution. Triggers compensation of prior
	 * steps.
	 */
	public ApprovalDecision reject(String sagaId, long journalSeq, String approverIdentity) {
		this.journal.append(sagaId, "approval-gate", Phase.REJECTED, "seq=" + journalSeq,
				"approver=" + approverIdentity);
		this.approvalStore.remove(sagaId, journalSeq);
		this.runner.compensate(sagaId);
		return new ApprovalDecision(sagaId, journalSeq, false, approverIdentity, Instant.now());
	}

	/**
	 * Resume a saga after a human has approved an IRREVERSIBLE tool, executing it
	 * with the supplied live payload. Performs stale-approval detection: if the
	 * live payload's SHA-256 hash does not match the hash recorded when the approval
	 * was originally requested, execution is rejected — even though a valid approval
	 * exists — and compensation runs.
	 *
	 * <p>This prevents a class of attack where the model re-plans between the time
	 * a human approves and the time the tool runs, substituting a different
	 * (potentially more dangerous) payload for the one the approver saw.
	 *
	 * <p><strong>Pass the unwrapped delegate callback</strong>, not the Sagacity-wrapped
	 * one, to avoid re-triggering the approval gate.
	 *
	 * @param sagaId           the saga to resume
	 * @param journalSeq       the journal sequence of the AWAITING_APPROVAL entry
	 * @param livePayload      the actual tool input that will be executed
	 * @param delegateCallback the raw (unwrapped) ToolCallback to execute
	 * @return SagaResult reflecting COMPLETED or COMPENSATED
	 */
	public SagaResult<String> resumeSaga(String sagaId, long journalSeq,
			String livePayload, org.springframework.ai.tool.ToolCallback delegateCallback) {

		// Locate the original approval request (still in store until we remove it)
		var maybeRequest = this.approvalStore.find(sagaId, journalSeq);
		if (maybeRequest.isEmpty()) {
			throw new IllegalStateException(
					"No pending approval found for saga=" + sagaId + " seq=" + journalSeq);
		}

		ApprovalRequest request = maybeRequest.get();

		// A pending request is not an approval. approve() leaves the request in the
		// store so this method can verify the payload, which means store state alone
		// cannot distinguish "approved" from "never looked at". Require the journaled
		// APPROVED decision before going any further.
		if (approverFor(sagaId, journalSeq).isEmpty()) {
			return rejectAndCompensate(sagaId, journalSeq, request.toolName(),
					"no approval recorded for this saga/seq",
					"Tool execution refused: no human approval recorded for saga=" + sagaId
							+ " seq=" + journalSeq);
		}

		// Stale-approval check — hash the live payload and compare. An absent hash is
		// treated as a failed check, not a skipped one: a request that never recorded
		// what was approved cannot be shown to match.
		String liveHash = HashChain.sha256(livePayload);
		if (request.inputHash().isEmpty()) {
			return rejectAndCompensate(sagaId, journalSeq, request.toolName(),
					"approval carries no payload hash — cannot verify",
					"Approval rejected: request has no recorded payload hash to verify against");
		}
		if (!liveHash.equals(request.inputHash())) {
			// Payload has changed since approval was granted — reject and compensate
			return rejectAndCompensate(sagaId, journalSeq, request.toolName(),
					"stale-approval: payload changed since approval was granted",
					"Stale approval rejected: payload changed since approval was granted");
		}

		// Payload matches — safe to execute
		this.approvalStore.remove(sagaId, journalSeq);
		this.journal.append(sagaId, request.toolName(), Phase.INTENT, livePayload, "");
		try {
			String result = delegateCallback.call(livePayload);
			this.journal.append(sagaId, request.toolName(), Phase.EXECUTED, livePayload,
					result != null ? result : "");
			return SagaResult.completed(sagaId, result);
		}
		catch (RuntimeException ex) {
			Throwable rootCause = ex.getCause() != null ? ex.getCause() : ex;
			String detail = rootCause.getMessage() != null ? rootCause.getMessage()
					: rootCause.getClass().getSimpleName();
			this.journal.append(sagaId, request.toolName(), Phase.FAILED, livePayload, detail);
			CompensationReport report = this.runner.compensate(sagaId);
			return SagaResult.compensated(sagaId, report, rootCause);
		}
	}

	/**
	 * Journals a REJECTED decision, drops the pending request, and compensates the
	 * saga exactly once. Compensation is not idempotent — {@link CompensationRunner}
	 * re-runs every EXECUTED effect it finds — so the report must come from a single
	 * call, never from a second one made while building the result.
	 */
	private SagaResult<String> rejectAndCompensate(String sagaId, long journalSeq, String toolName,
			String journalDetail, String failureMessage) {
		this.journal.append(sagaId, toolName, Phase.REJECTED, "seq=" + journalSeq, journalDetail);
		this.approvalStore.remove(sagaId, journalSeq);
		CompensationReport report = this.runner.compensate(sagaId);
		return SagaResult.compensated(sagaId, report, new IllegalStateException(failureMessage));
	}

	/**
	 * Returns the identity that approved this saga/seq, or empty if no APPROVED
	 * decision was journaled. Reads the journal rather than a side table so the
	 * decision that gates execution is the same one covered by the hash chain.
	 */
	private java.util.Optional<String> approverFor(String sagaId, long journalSeq) {
		String seqMarker = "seq=" + journalSeq;
		return this.journal.entries(sagaId)
			.stream()
			.filter(entry -> entry.phase() == Phase.APPROVED && seqMarker.equals(entry.input()))
			.map(entry -> entry.payload().startsWith("approver=")
					? entry.payload().substring("approver=".length()) : entry.payload())
			.findFirst();
	}

	/** Get all pending approval requests. */
	public List<ApprovalRequest> pendingApprovals() {
		return this.approvalStore.pendingRequests();
	}

	/** Get pending approval requests for a specific saga. */
	public List<ApprovalRequest> pendingApprovals(String sagaId) {
		return this.approvalStore.pendingRequests(sagaId);
	}

	/** Export journal as JSON Lines for audit compliance. */
	public String exportAuditLog(String sagaId) {
		return this.auditExporter.exportJsonLines(sagaId);
	}

	/** Verify journal integrity (tamper detection). */
	public AuditExporter.VerificationResult verifyJournal(String sagaId) {
		return this.auditExporter.verify(sagaId);
	}

	public SideEffectJournal journal() {
		return this.journal;
	}

	public ApprovalStore approvalStore() {
		return this.approvalStore;
	}

}
