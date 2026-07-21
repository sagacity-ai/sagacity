package dev.sagacity.springai;

import dev.sagacity.core.compensation.CompensationReport;
import dev.sagacity.core.saga.SagaStatus;

/**
 * Outcome of a saga run.
 *
 * @param <T> the type returned by the agent work
 */
public final class SagaResult<T> {

	private final String sagaId;

	private final SagaStatus status;

	private final T value;

	private final CompensationReport report;

	private final Throwable failure;

	private final String awaitingToolName;

	private SagaResult(String sagaId, SagaStatus status, T value, CompensationReport report, Throwable failure,
			String awaitingToolName) {
		this.sagaId = sagaId;
		this.status = status;
		this.value = value;
		this.report = report;
		this.failure = failure;
		this.awaitingToolName = awaitingToolName;
	}

	static <T> SagaResult<T> completed(String sagaId, T value) {
		return new SagaResult<>(sagaId, SagaStatus.COMPLETED, value, null, null, null);
	}

	static <T> SagaResult<T> compensated(String sagaId, CompensationReport report, Throwable failure) {
		SagaStatus status = report.allSucceeded() ? SagaStatus.COMPENSATED : SagaStatus.COMPENSATION_FAILED;
		return new SagaResult<>(sagaId, status, null, report, failure, null);
	}

	static <T> SagaResult<T> awaitingApproval(String sagaId, String toolName) {
		return new SagaResult<>(sagaId, SagaStatus.AWAITING_APPROVAL, null, null, null, toolName);
	}

	public String sagaId() {
		return this.sagaId;
	}

	public SagaStatus status() {
		return this.status;
	}

	public T value() {
		return this.value;
	}

	/** The compensation report, null when status is COMPLETED or AWAITING_APPROVAL. */
	public CompensationReport report() {
		return this.report;
	}

	/** Alias for {@link #report()} for clarity. */
	public CompensationReport compensationReport() {
		return this.report;
	}

	public Throwable failure() {
		return this.failure;
	}

	/** The tool name that triggered the approval gate, null unless status is AWAITING_APPROVAL. */
	public String awaitingToolName() {
		return this.awaitingToolName;
	}

}
