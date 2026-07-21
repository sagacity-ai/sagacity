package dev.sagacity.springai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import dev.sagacity.core.compensation.CompensationRegistry;
import dev.sagacity.core.compensation.CompensationReport;
import dev.sagacity.core.compensation.CompensationRunner;
import dev.sagacity.core.journal.InMemorySideEffectJournal;
import dev.sagacity.core.journal.SideEffectJournal;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Entry point. Wrap tool beans once, then run agent work inside saga scopes:
 *
 * <pre>{@code
 * Sagacity sagacity = Sagacity.create();
 * ToolCallback[] tools = sagacity.wrap(new RefundTools());
 *
 * SagaResult<ChatResponse> result = sagacity.saga("refund-order-123",
 *         () -> chatClient.prompt().user("...").toolCallbacks(tools).call().chatResponse());
 * }</pre>
 */
public final class Sagacity {

	private final SideEffectJournal journal;

	private final CompensationRegistry registry = new CompensationRegistry();

	private final CompensationRunner runner;

	private Sagacity(SideEffectJournal journal) {
		this.journal = journal;
		this.runner = new CompensationRunner(journal, this.registry);
	}

	/** In-memory journal — tests and demos. Durable journals arrive in M1. */
	public static Sagacity create() {
		return new Sagacity(new InMemorySideEffectJournal());
	}

	public static Sagacity create(SideEffectJournal journal) {
		return new Sagacity(journal);
	}

	/**
	 * Builds Spring AI tool callbacks from {@code @Tool}-annotated beans, registers
	 * their {@code @Compensable} declarations (failing fast on broken ones), and
	 * returns every callback wrapped with journaling.
	 */
	public ToolCallback[] wrap(Object... toolBeans) {
		List<ToolCallback> wrapped = new ArrayList<>();
		for (Object toolBean : toolBeans) {
			CompensationScanner.scan(toolBean, this.registry);
			ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
				.toolObjects(toolBean)
				.build()
				.getToolCallbacks();
			for (ToolCallback callback : callbacks) {
				wrapped.add(new SagacityToolCallback(callback, this.journal));
			}
		}
		return wrapped.toArray(ToolCallback[]::new);
	}

	/**
	 * Runs agent work in a saga scope. If the work throws, or any wrapped tool
	 * failed (even when the failure was swallowed and fed back to the model as an
	 * error message), compensations run in reverse order of execution.
	 */
	public <T> SagaResult<T> saga(String sagaId, Supplier<T> work) {
		SagaScope.open(sagaId);
		try {
			T value;
			try {
				value = work.get();
			}
			catch (RuntimeException ex) {
				SagaScope.markFailed(ex);
				CompensationReport report = this.runner.compensate(sagaId);
				return SagaResult.compensated(sagaId, report, SagaScope.failure());
			}
			if (SagaScope.failure() != null) {
				CompensationReport report = this.runner.compensate(sagaId);
				return SagaResult.compensated(sagaId, report, SagaScope.failure());
			}
			return SagaResult.completed(sagaId, value);
		}
		finally {
			SagaScope.close();
		}
	}

	public SagaResult<Void> saga(String sagaId, Runnable work) {
		return saga(sagaId, () -> {
			work.run();
			return null;
		});
	}

	public SideEffectJournal journal() {
		return this.journal;
	}

}
