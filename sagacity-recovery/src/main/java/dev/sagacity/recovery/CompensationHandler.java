package dev.sagacity.recovery;

/** Executes the declared undo for one tool's effect. */
@FunctionalInterface
public interface CompensationHandler {

	void compensate(CompensationContext context) throws Exception;

}
