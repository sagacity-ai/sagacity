package dev.sagacity.springai;

/**
 * Thread-bound saga context. Wrapped tool callbacks read the active saga id from
 * here; failure of any wrapped tool marks the scope failed even when Spring AI's
 * ToolExecutionExceptionProcessor swallows the exception and converts it into an
 * error message for the model (the default behavior of DefaultToolCallingManager).
 */
final class SagaScope {

	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

	private SagaScope() {
	}

	static void open(String sagaId) {
		if (CURRENT.get() != null) {
			throw new IllegalStateException(
					"A saga is already active on this thread: " + CURRENT.get().sagaId + " (nested sagas: M4)");
		}
		CURRENT.set(new Context(sagaId));
	}

	static void close() {
		CURRENT.remove();
	}

	static String currentSagaId() {
		Context context = CURRENT.get();
		return context != null ? context.sagaId : null;
	}

	static void markFailed(Throwable failure) {
		Context context = CURRENT.get();
		if (context != null && context.failure == null) {
			context.failure = failure;
		}
	}

	static Throwable failure() {
		Context context = CURRENT.get();
		return context != null ? context.failure : null;
	}

	private static final class Context {

		private final String sagaId;

		private Throwable failure;

		private Context(String sagaId) {
			this.sagaId = sagaId;
		}

	}

}
