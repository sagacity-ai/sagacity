package dev.sagacity.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;

/**
 * MCP tool: append an entry to the Sagacity tamper-evident audit trail.
 *
 * <p>Agents call this tool before and after every side-effecting tool call
 * to produce a complete, hash-chained record of what the agent did. Each
 * entry is SHA-256 chained to the previous, making post-hoc tampering detectable.
 *
 * <p>Usage pattern:
 * <pre>
 *   1. Call log_tool_call(sagaId, toolName, input, "INTENT")   — before executing
 *   2. Execute the real tool
 *   3. Call log_tool_call(sagaId, toolName, result, "EXECUTED") — after success
 *      or log_tool_call(sagaId, toolName, error, "FAILED")      — after failure
 * </pre>
 */
@Component
public class AuditTool {

    private final SideEffectJournal journal;

    public AuditTool(SideEffectJournal journal) {
        this.journal = journal;
    }

    /**
     * Append a journal entry to the tamper-evident audit trail for a saga.
     *
     * @param sagaId   unique identifier for the saga (agent workflow run)
     * @param toolName name of the tool whose effect is being journaled
     * @param payload  tool input (for INTENT) or output/error (for EXECUTED/FAILED)
     * @param phase    lifecycle phase: INTENT, EXECUTED, FAILED, COMPENSATED,
     *                 AWAITING_APPROVAL, APPROVED, or REJECTED
     * @return confirmation string with the saga ID and assigned sequence number
     */
    @Tool(description = """
            Append an entry to the Sagacity tamper-evident audit trail.
            Call with phase=INTENT before a tool executes, then phase=EXECUTED on success
            or phase=FAILED on error. Each entry is SHA-256 hash-chained to the previous,
            making the audit trail tamper-evident for EU AI Act Article 12 compliance.
            """)
    public String logToolCall(
            @ToolParam(description = "Unique ID for this agent workflow run (saga)") String sagaId,
            @ToolParam(description = "Name of the tool being journaled") String toolName,
            @ToolParam(description = "Tool input JSON (for INTENT) or output/error (for EXECUTED/FAILED)") String payload,
            @ToolParam(description = "Lifecycle phase: INTENT | EXECUTED | FAILED | COMPENSATED | AWAITING_APPROVAL | APPROVED | REJECTED") String phase) {

        Phase journalPhase;
        try {
            journalPhase = Phase.valueOf(phase.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return "error: unknown phase '" + phase + "'. Valid values: "
                    + "INTENT, EXECUTED, FAILED, COMPENSATED, AWAITING_APPROVAL, APPROVED, REJECTED";
        }

        var entry = journal.append(sagaId, toolName, journalPhase, payload, "");
        return "logged: sagaId=" + sagaId + " seq=" + entry.seq() + " phase=" + journalPhase;
    }
}
