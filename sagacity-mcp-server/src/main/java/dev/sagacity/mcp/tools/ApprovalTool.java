package dev.sagacity.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.sagacity.core.approval.ApprovalRequest;
import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;

/**
 * MCP tool: request human approval before an irreversible action.
 *
 * <p>When an agent is about to perform an action that cannot be undone
 * (send an email, charge a card, delete a record), it calls this tool first.
 * The approval request is persisted durably. The agent should then pause and
 * poll {@code check_approval_status} or wait for a webhook before proceeding.
 *
 * <p>The approval is cryptographically bound to the exact input payload via
 * SHA-256 hash. If the model re-plans and changes the payload, the approval
 * is invalid — the agent must request a new approval.
 */
@Component
public class ApprovalTool {

    private final ApprovalStore approvalStore;

    private final SideEffectJournal journal;

    public ApprovalTool(ApprovalStore approvalStore, SideEffectJournal journal) {
        this.approvalStore = approvalStore;
        this.journal = journal;
    }

    /**
     * Request human approval before executing an irreversible tool.
     *
     * <p>Records an AWAITING_APPROVAL journal entry and a pending ApprovalRequest.
     * Returns immediately with a pending status — the agent must not proceed until
     * a human approves via the Sagacity approval UI or REST endpoint.
     *
     * @param sagaId     unique ID for this agent workflow run
     * @param journalSeq the journal sequence number of the INTENT entry for this tool
     * @param toolName   name of the irreversible tool awaiting approval
     * @param input      exact JSON input that will be passed to the tool on approval
     * @return status string indicating the approval is pending, with the sagaId
     */
    @Tool(description = """
            Request human approval before executing an irreversible action.
            Call this before any tool that cannot be undone (send email, charge card,
            delete data). The saga will be suspended until a human approves or rejects
            via the Sagacity approval UI. The approval is bound to the exact input
            payload — if the input changes, a new approval is required.
            """)
    public String requestApproval(
            @ToolParam(description = "Unique ID for this agent workflow run (saga)") String sagaId,
            @ToolParam(description = "Journal sequence number from the prior INTENT log entry") long journalSeq,
            @ToolParam(description = "Name of the irreversible tool awaiting approval") String toolName,
            @ToolParam(description = "Exact JSON input that will be executed on approval") String input) {

        // Journal the suspension
        journal.append(sagaId, toolName, Phase.AWAITING_APPROVAL, input, "");

        // Persist the approval request durably
        approvalStore.save(new ApprovalRequest(sagaId, journalSeq, toolName, input));

        return "approval_pending: sagaId=" + sagaId + " seq=" + journalSeq
                + " tool=" + toolName
                + " | Approve or reject at /sagacity/approvals";
    }

    /**
     * Check whether a previously requested approval has been decided.
     *
     * @param sagaId     the saga to check
     * @param journalSeq the journal sequence number of the approval request
     * @return PENDING, APPROVED, or REJECTED with the saga and seq
     */
    @Tool(description = """
            Check the status of a pending approval request.
            Returns PENDING if a human has not yet decided, APPROVED if approved,
            or NOT_FOUND if no approval request exists for this saga+seq.
            """)
    public String checkApprovalStatus(
            @ToolParam(description = "Unique ID for this agent workflow run (saga)") String sagaId,
            @ToolParam(description = "Journal sequence number of the approval request") long journalSeq) {

        return approvalStore.find(sagaId, journalSeq)
            .map(req -> "PENDING: sagaId=" + sagaId + " seq=" + journalSeq + " tool=" + req.toolName())
            .orElse("NOT_FOUND: sagaId=" + sagaId + " seq=" + journalSeq
                    + " | Either approved/rejected (check journal) or never requested");
    }
}
