package dev.sagacity.core.compensation;

/** Executes the declared undo for one tool's effect. */
@FunctionalInterface
public interface CompensationHandler {

	void compensate(CompensationContext context) throws Exception;

}
