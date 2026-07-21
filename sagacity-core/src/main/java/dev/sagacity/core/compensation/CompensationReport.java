package dev.sagacity.core.compensation;

import java.util.List;

/**
 * Outcome of one compensation run, in execution order (i.e. reverse of the
 * original effect order).
 */
public record CompensationReport(String sagaId, List<Outcome> outcomes) {

	public enum Result {

		COMPENSATED, COMPENSATION_FAILED,

		/** Tool declared no compensation (or is IRREVERSIBLE) — nothing was run. */
		SKIPPED

	}

	public record Outcome(String toolName, long effectSeq, Result result, String detail) {
	}

	public boolean allSucceeded() {
		return this.outcomes.stream().noneMatch(o -> o.result() == Result.COMPENSATION_FAILED);
	}

}
