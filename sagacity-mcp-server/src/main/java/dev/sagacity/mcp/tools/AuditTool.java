package dev.sagacity.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.sagacity.core.journal.AuditStore;
import dev.sagacity.core.journal.Phase;

/**
 * MCP tool: append an entry to the Sagacity tamper-evident audit trail.
 */
@Component
public class AuditTool {

    private final AuditStore auditStore;

    public AuditTool(AuditStore auditStore) {
        this.auditStore = auditStore;
    }

    /**
     * Append a journal entry to the tamper-evident audit trail for a saga.
     *
     * @param sagaId   unique identifier for the saga
     * @param toolName name of the tool being journaled
     * @param input    tool input JSON (for Intent) or output/error (for Executed/Failed)
     * @param phase    lifecycle phase: Intent | Executed | Failed | Compensated |
     *                 CompensationFailed | AwaitingApproval | Approved | Rejected
     * @param data     phase-specific data — result for Executed, error for Failed/Rejected, else empty
     * @return confirmation with saga ID and assigned sequence number
     */
    @Tool(description = """
            Append an entry to the Sagacity tamper-evident audit trail.
            Call with phase=Intent before a tool executes, then phase=Executed on success
            (data=result) or phase=Failed on error (data=errorMessage).
            Each entry is SHA-256 hash-chained to the previous.
            """)
    public String logToolCall(
            @ToolParam(description = "Unique ID for this agent workflow run (saga)") String sagaId,
            @ToolParam(description = "Name of the tool being journaled") String toolName,
            @ToolParam(description = "Tool input JSON") String input,
            @ToolParam(description = "Phase: Intent | Executed | Failed | Compensated | CompensationFailed | AwaitingApproval | Approved | Rejected") String phase,
            @ToolParam(description = "Phase-specific data: result for Executed, error for Failed/CompensationFailed, reason for Rejected, empty otherwise") String data) {

        Phase journalPhase;
        try {
            journalPhase = Phase.fromStorage(phase, data != null && !data.isBlank()
                    ? "{\"result\":\"" + data + "\"}"   // best-effort: callers use data for all variants
                    : "{}");
        } catch (IllegalArgumentException e) {
            return "error: unknown phase '" + phase + "'. Valid values: "
                    + "Intent, Executed, Failed, Compensated, CompensationFailed, "
                    + "AwaitingApproval, Approved, Rejected";
        }

        var entry = auditStore.append(sagaId, toolName, journalPhase, input != null ? input : "");
        return "logged: sagaId=" + sagaId + " seq=" + entry.seq()
                + " phase=" + journalPhase.discriminator();
    }
}
