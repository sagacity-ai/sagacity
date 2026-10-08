package dev.sagacity.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.sagacity.core.compensation.CompensationReport;
import dev.sagacity.core.compensation.CompensationRunner;
import dev.sagacity.core.journal.SideEffectJournal;

/**
 * MCP tool: trigger compensation (rollback) for a failed saga.
 *
 * <p>When a saga fails mid-way, previously executed steps need to be undone.
 * The agent calls this tool with the sagaId. The CompensationRunner walks the
 * journal in reverse order and runs the declared compensation for each EXECUTED
 * step, journaling each outcome.
 *
 * <p><strong>Note:</strong> When using Sagacity via MCP (framework-agnostic mode),
 * compensation handlers are not registered on the server — they run inside the
 * agent's own process. This tool records the compensation signal in the journal
 * and returns a report of which tools were compensated or skipped. The agent is
 * responsible for executing the actual rollback logic.
 */
@Component
public class CompensationTool {

    private final CompensationRunner compensationRunner;

    private final SideEffectJournal journal;

    public CompensationTool(CompensationRunner compensationRunner, SideEffectJournal journal) {
        this.compensationRunner = compensationRunner;
        this.journal = journal;
    }

    /**
     * Trigger rollback for a failed saga.
     *
     * <p>Walks the saga's EXECUTED journal entries in reverse order and runs
     * their registered compensation handlers. Each compensation outcome
     * (COMPENSATED or COMPENSATION_FAILED) is appended to the journal.
     *
     * @param sagaId the saga to compensate
     * @return a summary of which tools were compensated, skipped, or failed
     */
    @Tool(description = """
            Trigger compensation (rollback) for a failed agent workflow.
            Walks the saga's completed steps in reverse order and runs their
            declared compensations. Each outcome is journaled. Use this when
            a mid-workflow failure requires undoing previously executed steps
            (e.g., cancel a reservation after a payment failed).
            """)
    public String compensate(
            @ToolParam(description = "Unique ID of the saga (agent workflow run) to roll back") String sagaId) {

        // Check the saga has any entries at all
        var entries = journal.entries(sagaId);
        if (entries.isEmpty()) {
            return "error: no journal entries found for sagaId=" + sagaId
                    + " | Verify the sagaId is correct";
        }

        CompensationReport report = compensationRunner.compensate(sagaId);

        if (report.outcomes().isEmpty()) {
            return "compensated: sagaId=" + sagaId
                    + " | No EXECUTED steps found to compensate";
        }

        // Build a readable summary
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
     * List all journal entries for a saga — useful for debugging or building an audit view.
     *
     * @param sagaId the saga to inspect
     * @return JSON-like summary of all journal entries in order
     */
    @Tool(description = """
            List all journal entries for a saga in chronological order.
            Use this to inspect what an agent did, debug a failed workflow,
            or verify the audit trail before exporting a compliance report.
            """)
    public String listEntries(
            @ToolParam(description = "Unique ID of the saga to inspect") String sagaId) {

        var entries = journal.entries(sagaId);
        if (entries.isEmpty()) {
            return "no entries found for sagaId=" + sagaId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("entries for sagaId=").append(sagaId).append(" (").append(entries.size()).append(" total):\n");
        for (var entry : entries) {
            sb.append("  seq=").append(entry.seq())
              .append(" phase=").append(entry.phase())
              .append(" tool=").append(entry.toolName())
              .append(" at=").append(entry.timestamp())
              .append("\n");
        }
        return sb.toString().trim();
    }
}
