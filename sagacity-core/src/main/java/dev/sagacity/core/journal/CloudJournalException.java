package dev.sagacity.core.journal;

/**
 * Thrown when communication with the Sagacity Cloud journal API fails.
 *
 * <p>This is an unchecked exception because journal failures are fatal for the
 * current saga — the effect has already run but the record could not be written
 * (or read), which puts the saga in an indeterminate state that requires
 * operator intervention regardless.
 */
public final class CloudJournalException extends RuntimeException {

	public CloudJournalException(String message) {
		super(message);
	}

	public CloudJournalException(String message, Throwable cause) {
		super(message, cause);
	}
}
