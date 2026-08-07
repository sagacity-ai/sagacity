package dev.sagacity.autoconfigure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.sagacity.core.approval.ApprovalDecision;
import dev.sagacity.core.approval.ApprovalRequest;
import dev.sagacity.core.audit.AuditExporter;
import dev.sagacity.core.saga.SagaStatus;
import dev.sagacity.springai.Sagacity;
import dev.sagacity.springai.SagaResult;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Sagacity approval workflow and audit.
 *
 * <h2>Approval flow</h2>
 * <ol>
 *   <li>Agent hits an IRREVERSIBLE tool → saga suspends, approval request created.</li>
 *   <li>Operator calls {@code GET /sagacity/approvals} to see what is pending.</li>
 *   <li>Operator calls {@code POST /sagacity/approve/{sagaId}/{seq}} to grant approval.</li>
 *   <li>System calls {@code POST /sagacity/resume/{sagaId}/{seq}} with the live payload
 *       to actually execute the tool. Payload hash is verified against the hash stored
 *       at approval-request time — stale approval (payload changed) is rejected.</li>
 * </ol>
 */
@RestController
@RequestMapping("/sagacity")
@ConditionalOnProperty(prefix = "sagacity", name = "approval-endpoints-enabled",
        havingValue = "true", matchIfMissing = true)
public class SagacityApprovalController {

    private final Sagacity sagacity;

    public SagacityApprovalController(Sagacity sagacity) {
        this.sagacity = sagacity;
    }

    @GetMapping("/approvals")
    public List<ApprovalRequest> pendingApprovals() {
        return sagacity.pendingApprovals();
    }

    @GetMapping("/approvals/{sagaId}")
    public List<ApprovalRequest> pendingApprovals(@PathVariable String sagaId) {
        return sagacity.pendingApprovals(sagaId);
    }

    /**
     * Record human approval for a pending IRREVERSIBLE tool.
     * Does NOT execute the tool — call /resume after this to execute with
     * payload hash verification.
     *
     * <p>Body: {@code {"approver": "manager@company.com"}}
     */
    @PostMapping("/approve/{sagaId}/{journalSeq}")
    public ResponseEntity<ApprovalDecision> approve(
            @PathVariable String sagaId,
            @PathVariable long journalSeq,
            @RequestBody Map<String, String> body) {
        String approver = body.getOrDefault("approver", "unknown");
        ApprovalDecision decision = sagacity.approve(sagaId, journalSeq, approver);
        return ResponseEntity.ok(decision);
    }

    /**
     * Reject a pending IRREVERSIBLE tool. Triggers compensation of prior steps.
     *
     * <p>Body: {@code {"approver": "manager@company.com"}}
     */
    @PostMapping("/reject/{sagaId}/{journalSeq}")
    public ResponseEntity<ApprovalDecision> reject(
            @PathVariable String sagaId,
            @PathVariable long journalSeq,
            @RequestBody Map<String, String> body) {
        String approver = body.getOrDefault("approver", "unknown");
        ApprovalDecision decision = sagacity.reject(sagaId, journalSeq, approver);
        return ResponseEntity.ok(decision);
    }

    /**
     * Execute a tool that a human has approved, with payload verification.
     *
     * <p>Body: {@code {"payload": "{\"amount\":100,\"to\":\"alice\"}"}}
     *
     * <p>Returns 200 with the saga result when the tool ran, 409 when execution
     * was refused — no approval recorded, or the payload no longer matches the
     * one that was approved — and 404 when nothing is pending for this saga/seq.
     * A refusal is a completed action, not a server error: prior steps have been
     * compensated and the refusal is journaled.
     */
    @PostMapping("/resume/{sagaId}/{journalSeq}")
    public ResponseEntity<Map<String, Object>> resume(
            @PathVariable String sagaId,
            @PathVariable long journalSeq,
            @RequestBody Map<String, String> body) {
        String payload = body.get("payload");
        if (payload == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "body must contain a 'payload' field"));
        }

        SagaResult<String> result;
        try {
            result = sagacity.resumeSaga(sagaId, journalSeq, payload);
        }
        catch (IllegalStateException ex) {
            return ResponseEntity.status(404)
                    .body(Map.of("sagaId", sagaId, "journalSeq", journalSeq,
                            "error", String.valueOf(ex.getMessage())));
        }

        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("sagaId", result.sagaId());
        responseBody.put("status", result.status().name());
        if (result.status() == SagaStatus.COMPLETED) {
            responseBody.put("result", result.value());
            return ResponseEntity.ok(responseBody);
        }
        responseBody.put("reason", result.failure() != null
                ? result.failure().getMessage() : "execution refused");
        return ResponseEntity.status(409).body(responseBody);
    }

    @GetMapping("/audit/{sagaId}")
    public ResponseEntity<String> auditExport(@PathVariable String sagaId) {
        String jsonLines = sagacity.exportAuditLog(sagaId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/x-ndjson")
                .body(jsonLines);
    }

    @GetMapping("/audit/{sagaId}/verify")
    public AuditExporter.VerificationResult verifyAudit(@PathVariable String sagaId) {
        return sagacity.verifyJournal(sagaId);
    }
}
