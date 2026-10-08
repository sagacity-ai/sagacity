package dev.sagacity.springai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import dev.sagacity.core.journal.AuditStore;
import dev.sagacity.core.journal.HashChain;
import dev.sagacity.core.journal.InMemoryAuditStore;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.retry.RetryPolicy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Entry point for Sagacity governance. Wrap tool beans once, then run agent
 * work inside saga scopes:
 *
 * <pre>{@code
 * Sagacity sagacity = Sagacity.create();
 * ToolCallback[] tools = sagacity.wrap(new RefundTools());
 *
 * SagaResult<ChatResponse> result = sagacity.saga("refund-order-123",
 *     () -> chatClient.prompt().user("...").toolCallbacks(tools).call().chatResponse());
 * }</pre>
 */
public final class Sagacity {

    private final AuditStore auditStore;

    private final ApprovalStore approvalStore;

    private final CompensationRegistry registry = new CompensationRegistry();

    private final CompensationRunner runner;

    private final AuditExporter auditExporter;

    private final java.util.Map<String, ToolCallback> delegates =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final long retryInitialDelayMs;

    private final double retryBackoffMultiplier;

    private Sagacity(AuditStore auditStore, ApprovalStore approvalStore,
            long retryInitialDelayMs, double retryBackoffMultiplier) {
        this.auditStore = auditStore;
        this.approvalStore = approvalStore;
        this.runner = new CompensationRunner(auditStore, this.registry);
        this.auditExporter = new AuditExporter(auditStore);
        this.retryInitialDelayMs = retryInitialDelayMs;
        this.retryBackoffMultiplier = retryBackoffMultiplier;
    }

    /** In-memory store — for tests and demos only. */
    public static Sagacity create() {
        return new Sagacity(new InMemoryAuditStore(), new InMemoryApprovalStore(), 100L, 2.0);
    }

    public static Sagacity create(AuditStore auditStore) {
        return new Sagacity(auditStore, new InMemoryApprovalStore(), 100L, 2.0);
    }

    public static Sagacity create(AuditStore auditStore, ApprovalStore approvalStore) {
        return new Sagacity(auditStore, approvalStore, 100L, 2.0);
    }

    public static Sagacity create(AuditStore auditStore, ApprovalStore approvalStore,
            long retryInitialDelayMs, double retryBackoffMultiplier) {
        return new Sagacity(auditStore, approvalStore, retryInitialDelayMs, retryBackoffMultiplier);
    }

    /**
     * Wraps tool beans with journaling, approval gates, and retry logic.
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
                String toolName = callback.getToolDefinition().name();
                Reversibility rev = CompensationScanner.reversibilityFor(toolBean, toolName, this.registry);
                RetryPolicy retryPolicy = CompensationScanner.retryPolicyFor(
                        toolBean, toolName, this.retryInitialDelayMs, this.retryBackoffMultiplier);
                this.delegates.put(toolName, callback);
                wrapped.add(new SagacityToolCallback(callback, this.auditStore,
                        this.approvalStore, rev, retryPolicy));
            }
        }
        return wrapped.toArray(ToolCallback[]::new);
    }

    /**
     * Runs agent work in a saga scope with compensation on failure.
     */
    public <T> SagaResult<T> saga(String sagaId, Supplier<T> work) {
        SagaScope.open(sagaId);
        try {
            T value;
            try {
                value = work.get();
            } catch (RuntimeException ex) {
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
        } finally {
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
     * Approve a pending IRREVERSIBLE tool execution.
     */
    public ApprovalDecision approve(String sagaId, long journalSeq, String approverIdentity) {
        this.auditStore.append(sagaId, "approval-gate",
                new Phase.Approved(), "seq=" + journalSeq + "|approver=" + approverIdentity);
        return new ApprovalDecision(sagaId, journalSeq, true, approverIdentity, Instant.now());
    }

    /**
     * Reject a pending IRREVERSIBLE tool execution and trigger compensation.
     */
    public ApprovalDecision reject(String sagaId, long journalSeq, String approverIdentity) {
        this.auditStore.append(sagaId, "approval-gate",
                new Phase.Rejected("rejected by " + approverIdentity),
                "seq=" + journalSeq);
        this.approvalStore.remove(sagaId, journalSeq);
        this.runner.compensate(sagaId);
        return new ApprovalDecision(sagaId, journalSeq, false, approverIdentity, Instant.now());
    }

    /**
     * Resume a saga after human approval, executing the tool with payload verification.
     */
    public SagaResult<String> resumeSaga(String sagaId, long journalSeq,
            String livePayload, ToolCallback delegateCallback) {

        var maybeRequest = this.approvalStore.find(sagaId, journalSeq);
        if (maybeRequest.isEmpty()) {
            throw new IllegalStateException(
                    "No pending approval found for saga=" + sagaId + " seq=" + journalSeq);
        }

        ApprovalRequest request = maybeRequest.get();

        if (approverFor(sagaId, journalSeq).isEmpty()) {
            return rejectAndCompensate(sagaId, journalSeq, request.toolName(),
                    "no approval recorded for this saga/seq",
                    "Tool execution refused: no human approval recorded");
        }

        String liveHash = HashChain.sha256(livePayload);
        if (request.inputHash().isEmpty()) {
            return rejectAndCompensate(sagaId, journalSeq, request.toolName(),
                    "approval carries no payload hash — cannot verify",
                    "Approval rejected: no recorded payload hash to verify against");
        }
        if (!liveHash.equals(request.inputHash())) {
            return rejectAndCompensate(sagaId, journalSeq, request.toolName(),
                    "stale-approval: payload changed since approval was granted",
                    "Stale approval rejected: payload changed since approval was granted");
        }

        this.approvalStore.remove(sagaId, journalSeq);
        this.auditStore.append(sagaId, request.toolName(), new Phase.Intent(), livePayload);
        try {
            String result = delegateCallback.call(livePayload);
            this.auditStore.append(sagaId, request.toolName(),
                    new Phase.Executed(result != null ? result : ""), livePayload);
            return SagaResult.completed(sagaId, result);
        } catch (RuntimeException ex) {
            Throwable rootCause = ex.getCause() != null ? ex.getCause() : ex;
            String detail = rootCause.getMessage() != null
                    ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
            this.auditStore.append(sagaId, request.toolName(), new Phase.Failed(detail), livePayload);
            CompensationReport report = this.runner.compensate(sagaId);
            return SagaResult.compensated(sagaId, report, rootCause);
        }
    }

    public SagaResult<String> resumeSaga(String sagaId, long journalSeq, String livePayload) {
        ApprovalRequest request = this.approvalStore.find(sagaId, journalSeq)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending approval found for saga=" + sagaId + " seq=" + journalSeq));
        ToolCallback delegate = this.delegates.get(request.toolName());
        if (delegate == null) {
            throw new IllegalStateException("Tool '" + request.toolName()
                    + "' is not registered — was it passed to wrap()?");
        }
        return resumeSaga(sagaId, journalSeq, livePayload, delegate);
    }

    private SagaResult<String> rejectAndCompensate(String sagaId, long journalSeq,
            String toolName, String journalDetail, String failureMessage) {
        this.auditStore.append(sagaId, toolName,
                new Phase.Rejected(journalDetail), "seq=" + journalSeq);
        this.approvalStore.remove(sagaId, journalSeq);
        CompensationReport report = this.runner.compensate(sagaId);
        return SagaResult.compensated(sagaId, report, new IllegalStateException(failureMessage));
    }

    /**
     * Finds the approver identity from the journal using pattern matching on the sealed Phase.
     */
    private Optional<String> approverFor(String sagaId, long journalSeq) {
        String seqMarker = "seq=" + journalSeq;
        return this.auditStore.findBySagaId(sagaId, Phase.Approved.class)
                .stream()
                .filter(entry -> entry.input().contains(seqMarker))
                .map(entry -> {
                    // input format: "seq=N|approver=identity"
                    String input = entry.input();
                    int idx = input.indexOf("|approver=");
                    return idx >= 0 ? input.substring(idx + 10) : "unknown";
                })
                .findFirst();
    }

    public List<ApprovalRequest> pendingApprovals() {
        return this.approvalStore.pendingRequests();
    }

    public List<ApprovalRequest> pendingApprovals(String sagaId) {
        return this.approvalStore.pendingRequests(sagaId);
    }

    public String exportAuditLog(String sagaId) {
        return this.auditExporter.exportJsonLines(sagaId);
    }

    public AuditExporter.VerificationResult verifyJournal(String sagaId) {
        return this.auditExporter.verify(sagaId);
    }

    public AuditStore auditStore() {
        return this.auditStore;
    }

    public ApprovalStore approvalStore() {
        return this.approvalStore;
    }
}
