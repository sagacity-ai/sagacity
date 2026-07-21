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
	 * approver's identity.
	 */
	public ApprovalDecision approve(String sagaId, long journalSeq, String approverIdentity) {
		this.journal.append(sagaId, "approval-gate", Phase.APPROVED, "seq=" + journalSeq,
				"approver=" + approverIdentity);
		this.approvalStore.remove(sagaId, journalSeq);
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
