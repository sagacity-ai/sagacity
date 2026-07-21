package dev.sagacity.autoconfigure;

import java.util.List;
import java.util.Map;

import dev.sagacity.core.approval.ApprovalDecision;
import dev.sagacity.core.approval.ApprovalRequest;
import dev.sagacity.core.audit.AuditExporter;
import dev.sagacity.springai.Sagacity;

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
 */
@RestController
@RequestMapping("/sagacity")
@ConditionalOnProperty(prefix = "sagacity", name = "approval-endpoints-enabled", havingValue = "true", matchIfMissing = true)
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

    @PostMapping("/approve/{sagaId}/{journalSeq}")
    public ResponseEntity<ApprovalDecision> approve(
            @PathVariable String sagaId,
            @PathVariable long journalSeq,
            @RequestBody Map<String, String> body) {
        String approver = body.getOrDefault("approver", "unknown");
        ApprovalDecision decision = sagacity.approve(sagaId, journalSeq, approver);
        return ResponseEntity.ok(decision);
    }

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
