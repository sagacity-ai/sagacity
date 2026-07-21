package dev.sagacity.springai;

import java.util.ArrayList;
import java.util.List;

import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.saga.SagaStatus;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagacitySagaTest {

	/**
	 * The canonical Sagacity example, used across tests, demos, and docs:
	 * an agent places an order in three steps — reserve inventory, create the
	 * order, schedule the shipment — and shipment scheduling fails.
	 */
	static class OrderTools {

		final List<String> events = new ArrayList<>();

		@Tool(description = "Reserve inventory for a product")
		@Compensable(by = "releaseInventory")
		public String reserveInventory(String productId, int quantity) {
			this.events.add("reserve:" + productId + ":" + quantity);
			return "reservation-84";
		}

		@Compensation
		public void releaseInventory(CompensationContext context) {
			this.events.add("release:" + context.result());
		}

		@Tool(description = "Create the customer order")
		@Compensable(by = "cancelOrder")
		public String createOrder(String productId, int quantity) {
			this.events.add("create:" + productId);
			return "order-123";
		}

		@Compensation
		public void cancelOrder(CompensationContext context) {
			this.events.add("cancel:" + context.result());
		}

		@Tool(description = "Schedule a shipment for the order")
		public String scheduleShipment(String orderId) {
			throw new IllegalStateException("no carrier available");
		}

	}

	private static final String ORDER_INPUT = "{\"productId\":\"p-1\",\"quantity\":2}";

	private static ToolCallback byName(ToolCallback[] callbacks, String name) {
		for (ToolCallback callback : callbacks) {
			if (callback.getToolDefinition().name().equals(name)) {
				return callback;
			}
		}
		throw new IllegalArgumentException("no tool named " + name);
	}

	@Test
	void failedSagaCompensatesExecutedStepsInReverseOrder() {
		Sagacity sagacity = Sagacity.create();
		OrderTools tools = new OrderTools();
		ToolCallback[] callbacks = sagacity.wrap(tools);

		SagaResult<Void> result = sagacity.saga("place-order-1", () -> {
			byName(callbacks, "reserveInventory").call(ORDER_INPUT);
			byName(callbacks, "createOrder").call(ORDER_INPUT);
			byName(callbacks, "scheduleShipment").call("{\"orderId\":\"order-123\"}");
		});

		assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
		// result snapshots are the JSON-serialized tool results, hence the quotes
		assertThat(tools.events).containsExactly("reserve:p-1:2", "create:p-1", "cancel:\"order-123\"",
				"release:\"reservation-84\"");
		assertThat(sagacity.journal().entries("place-order-1")).extracting(e -> e.toolName() + ":" + e.phase())
			.containsExactly("reserveInventory:" + Phase.INTENT, "reserveInventory:" + Phase.EXECUTED,
					"createOrder:" + Phase.INTENT, "createOrder:" + Phase.EXECUTED,
					"scheduleShipment:" + Phase.INTENT, "scheduleShipment:" + Phase.FAILED,
					"createOrder:" + Phase.COMPENSATED, "reserveInventory:" + Phase.COMPENSATED);
	}

	@Test
	void successfulSagaCompensatesNothing() {
		Sagacity sagacity = Sagacity.create();
		OrderTools tools = new OrderTools();
		ToolCallback[] callbacks = sagacity.wrap(tools);

		SagaResult<Void> result = sagacity.saga("place-order-2", () -> {
			byName(callbacks, "reserveInventory").call(ORDER_INPUT);
			byName(callbacks, "createOrder").call(ORDER_INPUT);
		});

		assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
		assertThat(tools.events).containsExactly("reserve:p-1:2", "create:p-1");
	}

	@Test
	void outsideSagaScopeToolsPassThroughUnjournaled() {
		Sagacity sagacity = Sagacity.create();
		OrderTools tools = new OrderTools();
		ToolCallback[] callbacks = sagacity.wrap(tools);

		String result = byName(callbacks, "reserveInventory").call(ORDER_INPUT);

		assertThat(result).contains("reservation-84");
		assertThat(sagacity.journal().entries("place-order-3")).isEmpty();
	}

	@Test
	void brokenCompensationDeclarationFailsAtWrapTimeNotAtIncidentTime() {
		class BrokenTools {

			@Tool(description = "x")
			@Compensable(by = "doesNotExist")
			public String act(String id) {
				return "ok";
			}

		}

		assertThatThrownBy(() -> Sagacity.create().wrap(new BrokenTools())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("doesNotExist");
	}

	/**
	 * The design-critical case: DefaultToolCallingManager's exception processor
	 * swallows tool failures and turns them into error messages for the model, so
	 * nothing throws out of executeToolCalls. Sagacity must detect the failure
	 * anyway — it decorates the ToolCallback, inside the manager's catch.
	 */
	@Test
	void sagaDetectsToolFailureEvenWhenSpringAiSwallowsIt() {
		Sagacity sagacity = Sagacity.create();
		OrderTools tools = new OrderTools();
		ToolCallback[] callbacks = sagacity.wrap(tools);

		ToolCallingManager manager = DefaultToolCallingManager.builder().build();
		Prompt prompt = new Prompt(List.of(new UserMessage("ship order order-123")),
				ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
		ChatResponse modelTurn = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "scheduleShipment",
					"{\"orderId\":\"order-123\"}")))
			.build())));

		SagaResult<Void> result = sagacity.saga("place-order-4", () -> {
			byName(callbacks, "reserveInventory").call(ORDER_INPUT);
			manager.executeToolCalls(prompt, modelTurn);
		});

		assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
		assertThat(result.failure()).hasMessageContaining("no carrier available");
		assertThat(tools.events).containsExactly("reserve:p-1:2", "release:\"reservation-84\"");
	}

}
