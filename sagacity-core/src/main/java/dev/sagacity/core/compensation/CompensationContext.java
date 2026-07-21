package dev.sagacity.core.compensation;

/**
 * What a compensation method gets to see about the effect it is undoing.
 *
 * @param sagaId the failed saga
 * @param toolName the tool whose effect is being compensated
 * @param input the original tool input (JSON)
 * @param result the original tool result snapshot
 */
public record CompensationContext(String sagaId, String toolName, String input, String result) {

}
