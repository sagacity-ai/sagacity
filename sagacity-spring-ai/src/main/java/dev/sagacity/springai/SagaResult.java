package dev.sagacity.springai;

import dev.sagacity.core.compensation.CompensationReport;
import dev.sagacity.core.saga.SagaStatus;

/**
 * Outcome of a saga run.
 *
 * @param value the work's return value, null unless status is COMPLETED
 * @param report outcome of the compensation run, null when status is COMPLETED
 * @param failure what failed the saga, null when status is COMPLETED
 */
public record SagaResult<T>(String sagaId, SagaStatus status, T value, CompensationReport report, Throwable failure) {

	static <T> SagaResult<T> completed(String sagaId, T value) {
		return new SagaResult<>(sagaId, SagaStatus.COMPLETED, value, null, null);
	}

	static <T> SagaResult<T> compensated(String sagaId, CompensationReport report, Throwable failure) {
		SagaStatus status = report.allSucceeded() ? SagaStatus.COMPENSATED : SagaStatus.COMPENSATION_FAILED;
		return new SagaResult<>(sagaId, status, null, report, failure);
	}

}
