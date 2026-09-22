package dev.sagacity.workflows.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.sagacity.workflows.WorkflowRun;
import dev.sagacity.workflows.WorkflowRuntime;
import dev.sagacity.workflows.WorkflowStatus;

/**
 * REST endpoints for workflow management and human gate approval.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /sagacity/workflows} — list all runs</li>
 *   <li>{@code GET  /sagacity/workflows/{runId}} — get a single run</li>
 *   <li>{@code POST /sagacity/workflows/{runId}/gates/{stageName}/approve} — approve a gate</li>
 *   <li>{@code POST /sagacity/workflows/{runId}/gates/{stageName}/reject} — reject a gate</li>
 * </ul>
 *
 * <p>Registered automatically by
 * {@link dev.sagacity.workflows.autoconfigure.WorkflowAutoConfiguration}
 * when {@code spring-webmvc} is on the classpath.
 */
@RestController
@RequestMapping("/sagacity/workflows")
public class HumanApprovalGateController {

    private final WorkflowRuntime runtime;

    public HumanApprovalGateController(WorkflowRuntime runtime) {
        this.runtime = runtime;
    }

    /** List all workflow runs. */
    @GetMapping
    public List<WorkflowRunView> listRuns() {
        return runtime.allRuns().stream()
                .map(WorkflowRunView::from)
                .toList();
    }

    /** Get a single workflow run by ID. */
    @GetMapping("/{runId}")
    public ResponseEntity<WorkflowRunView> getRun(@PathVariable String runId) {
        return runtime.findRun(runId)
                .map(WorkflowRunView::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Approve a pending gate. The workflow will resume execution of the named stage.
     *
     * <p>Request body is optional. If provided, {@code reason} is logged.
     */
    @PostMapping("/{runId}/gates/{stageName}/approve")
    public ResponseEntity<Map<String, String>> approveGate(
            @PathVariable String runId,
            @PathVariable String stageName,
            @RequestBody(required = false) GateDecisionRequest body) {
        try {
            runtime.approveGate(runId, stageName);
            return ResponseEntity.ok(Map.of(
                    "runId", runId,
                    "stageName", stageName,
                    "decision", "approved"));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Reject a pending gate. The workflow will fail and compensate completed stages.
     *
     * <p>Request body must contain {@code reason}.
     */
    @PostMapping("/{runId}/gates/{stageName}/reject")
    public ResponseEntity<Map<String, String>> rejectGate(
            @PathVariable String runId,
            @PathVariable String stageName,
            @RequestBody GateDecisionRequest body) {
        try {
            String reason = body != null && body.reason() != null ? body.reason() : "rejected via API";
            runtime.rejectGate(runId, stageName, reason);
            return ResponseEntity.ok(Map.of(
                    "runId", runId,
                    "stageName", stageName,
                    "decision", "rejected",
                    "reason", reason));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // View model — keeps WorkflowRun internals off the wire
    // -------------------------------------------------------------------------

    public record WorkflowRunView(
            String runId,
            String workflowName,
            WorkflowStatus status,
            int currentStageOrder,
            String pendingGateStageName,
            String failureReason,
            List<String> completedStages,
            String startedAt,
            String completedAt) {

        static WorkflowRunView from(WorkflowRun run) {
            return new WorkflowRunView(
                    run.runId(),
                    run.workflowName(),
                    run.status(),
                    run.currentStageOrder(),
                    run.pendingGateStageName().orElse(null),
                    run.failureReason().orElse(null),
                    run.completedStages(),
                    run.startedAt().toString(),
                    run.completedAt().map(Object::toString).orElse(null));
        }
    }

    public record GateDecisionRequest(String reason) {}
}
