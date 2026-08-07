package dev.sagacity.autoconfigure;

import java.util.List;
import java.util.Map;

import dev.sagacity.core.approval.ApprovalDecision;
import dev.sagacity.core.approval.ApprovalRequest;
import dev.sagacity.core.audit.AuditExporter;
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
 * <h3>Approval flow</h3>
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
