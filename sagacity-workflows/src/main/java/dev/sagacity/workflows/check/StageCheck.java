package dev.sagacity.workflows.check;

/**
 * SPI for pre-flight stage checks declared via {@link dev.sagacity.workflows.annotation.Check}.
 *
 * <p>Implement this interface and register it as a Spring bean. The workflow runtime
 * resolves check instances from the application context by type.
 */
@FunctionalInterface
public interface StageCheck {

    /**
     * Evaluate the check for the about-to-execute stage.
     *
     * @param ctx context containing workflow run ID, stage name, and stage input
     * @return {@link CheckResult#pass()} to allow execution, {@link CheckResult#fail(String)}
     *         to block it
     */
    CheckResult check(StageCheckContext ctx);
}
