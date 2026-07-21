package dev.sagacity.core.saga;

/** Terminal status of a saga run. */
public enum SagaStatus {

	/** The task finished; no compensation was needed. */
	COMPLETED,

	/** The task failed; all declared compensations ran successfully. */
	COMPENSATED,

	/** The task failed and at least one compensation also failed — needs a human. */
	COMPENSATION_FAILED

}
