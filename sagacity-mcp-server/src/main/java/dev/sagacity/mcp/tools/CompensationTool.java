package dev.sagacity.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.sagacity.recovery.CompensationReport;
import dev.sagacity.recovery.CompensationRunner;
import dev.sagacity.audit.AuditStore;

/**
 * MCP tools: trigger compensation and inspect audit entries for a saga.
 */
@Component
public class CompensationTool {

    private final CompensationRunner compensationRunner;
    private final AuditStore auditStore;

    public CompensationTool(CompensationRunner compensationRunner, AuditStore auditStore) {
        this.compensationRunner = compensationRunner;
        this.auditStore = auditStore;
    }

    /**
     * Trigger rollback for a failed saga.
     *
     * @param sagaId the saga to compensate
     * @return summary of compensation outcomes
     */
    @Tool(description = """
            Trigger compensation (rollback) for a failed agent workflow.
            Walks the saga's Executed steps in reverse and runs their compensations.
            """)
    public String compensate(
            @ToolParam(description = "Unique ID of the saga to roll back") String sagaId) {

        var entries = auditStore.findBySagaId(sagaId);
        if (entries.isEmpty()) {
            return "error: no journal entries found for sagaId=" + sagaId;
        }

        CompensationReport report = compensationRunner.compensate(sagaId);

        if (report.outcomes().isEmpty()) {
            return "compensated: sagaId=" + sagaId + " | No Executed steps found to compensate";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("compensated: sagaId=").append(sagaId).append("\n");
        for (CompensationReport.Outcome outcome : report.outcomes()) {
            sb.append("  tool=").append(outcome.toolName())
              .append(" seq=").append(outcome.effectSeq())
              .append(" result=").append(outcome.result());
            if (outcome.detail() != null && !outcome.detail().isEmpty()) {
                sb.append(" detail=").append(outcome.detail());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * List all journal entries for a saga in chronological order.
     *
     * @param sagaId the saga to inspect
     * @return summary of all journal entries
     */
    @Tool(description = """
            List all audit journal entries for a saga in chronological order.
            Useful for debugging or verifying the audit trail.
            """)
    public String listEntries(
            @ToolParam(description = "Unique ID of the saga to inspect") String sagaId) {

        var entries = auditStore.findBySagaId(sagaId);
        if (entries.isEmpty()) {
            return "no entries found for sagaId=" + sagaId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("entries for sagaId=").append(sagaId)
          .append(" (").append(entries.size()).append(" total):\n");
        for (var entry : entries) {
            sb.append("  seq=").append(entry.seq())
              .append(" phase=").append(entry.phase().discriminator())
              .append(" tool=").append(entry.toolName())
              .append(" at=").append(entry.timestamp())
              .append("\n");
        }
        return sb.toString().trim();
    }
}
