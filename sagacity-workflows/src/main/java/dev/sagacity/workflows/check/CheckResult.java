package dev.sagacity.workflows.check;

/**
 * Result of a {@link StageCheck} evaluation.
 */
public final class CheckResult {

    private final boolean passed;
    private final String reason;

    private CheckResult(boolean passed, String reason) {
        this.passed = passed;
        this.reason = reason;
    }

    /** The check passed — stage execution may proceed. */
    public static CheckResult pass() {
        return new CheckResult(true, "");
    }

    /**
     * The check failed — stage execution is blocked.
     *
     * @param reason human-readable explanation recorded in the audit trail
     */
    public static CheckResult fail(String reason) {
        return new CheckResult(false, reason != null ? reason : "check failed");
    }

    public boolean isPassed() {
        return passed;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return passed ? "CheckResult{PASS}" : "CheckResult{FAIL: " + reason + "}";
    }
}
