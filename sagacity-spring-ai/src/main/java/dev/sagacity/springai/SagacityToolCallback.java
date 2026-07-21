package dev.sagacity.springai;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.approval.ApprovalRequest;
import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorates a Spring AI {@link ToolCallback} with side-effect journaling and
 * approval gate support for IRREVERSIBLE tools.
 *
 * <p>Decoration happens at the callback level — not at ToolCallingManager level —
 * deliberately: DefaultToolCallingManager catches ToolExecutionException and
 * converts it to an error message for the model, so a manager-level decorator
 * never observes the raw failure. This wrapper sits inside that catch and sees it
 * first: it journals FAILED, marks the saga scope failed, then rethrows so Spring
 * AI's normal error handling still applies.
 *
 * <p>Outside a saga scope the wrapper is a pass-through: the tool stays usable in
 * non-saga flows and nothing is journaled.
 */
public final class SagacityToolCallback implements ToolCallback {

	private final ToolCallback delegate;

	private final SideEffectJournal journal;

	private final ApprovalStore approvalStore;

	private final Reversibility reversibility;

	SagacityToolCallback(ToolCallback delegate, SideEffectJournal journal, ApprovalStore approvalStore,
			Reversibility reversibility) {
		this.delegate = delegate;
		this.journal = journal;
		this.approvalStore = approvalStore;
		this.reversibility = reversibility;
	}

	/** Backward-compatible constructor (no approval gate). */
	SagacityToolCallback(ToolCallback delegate, SideEffectJournal journal) {
		this(delegate, journal, null, Reversibility.COMPENSATABLE);
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		String sagaId = SagaScope.currentSagaId();
		if (sagaId == null) {
			return this.delegate.call(toolInput, toolContext);
		}

		String toolName = getToolDefinition().name();

		// Approval gate for IRREVERSIBLE tools
		if (this.reversibility == Reversibility.IRREVERSIBLE && this.approvalStore != null) {
			var intentEntry = this.journal.append(sagaId, toolName, Phase.AWAITING_APPROVAL, toolInput, "");
			this.approvalStore.save(new ApprovalRequest(sagaId, intentEntry.seq(), toolName, toolInput));
			SagaScope.markAwaitingApproval(toolName, intentEntry.seq());
			return "[AWAITING_APPROVAL] Tool '" + toolName + "' requires human approval before execution.";
		}

		this.journal.append(sagaId, toolName, Phase.INTENT, toolInput, "");
		try {
			String result = this.delegate.call(toolInput, toolContext);
			this.journal.append(sagaId, toolName, Phase.EXECUTED, toolInput, result != null ? result : "");
			return result;
		}
		catch (RuntimeException ex) {
			Throwable rootCause = ex.getCause() != null ? ex.getCause() : ex;
			String detail = rootCause.getMessage() != null ? rootCause.getMessage()
					: rootCause.getClass().getSimpleName();
			this.journal.append(sagaId, toolName, Phase.FAILED, toolInput, detail);
			SagaScope.markFailed(rootCause);
			throw ex;
		}
	}

}
