package dev.sagacity.examples;

import java.util.List;

import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.springai.SagaResult;
import dev.sagacity.springai.Sagacity;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

/**
 * The canonical Sagacity demo: an agent places an order in three steps —
 * reserve inventory, create the order, schedule the shipment. Shipment
 * scheduling fails, and Sagacity compensates the first two steps in reverse
 * order, with the journal as evidence.
 *
 * Run with: mvn -pl sagacity-examples exec:java
 */
public final class PlaceOrderDemo {

	static class OrderTools {

		@Tool(description = "Reserve inventory for a product")
		@Compensable(by = "releaseInventory")
		public String reserveInventory(String productId, int quantity) {
			System.out.println("  [tool] reserved " + quantity + " x " + productId);
			return "reservation-84";
		}

		@Compensation
		public void releaseInventory(CompensationContext context) {
			System.out.println("  [undo] released " + context.result());
		}

		@Tool(description = "Create the customer order")
		@Compensable(by = "cancelOrder")
		public String createOrder(String productId, int quantity) {
			System.out.println("  [tool] created order for " + productId);
			return "order-123";
		}

		@Compensation
		public void cancelOrder(CompensationContext context) {
			System.out.println("  [undo] cancelled " + context.result());
		}

		@Tool(description = "Schedule a shipment for the order")
		public String scheduleShipment(String orderId) {
			System.out.println("  [tool] scheduling shipment...");
			throw new IllegalStateException("no carrier available");
		}

	}

	public static void main(String[] args) {
		Sagacity sagacity = Sagacity.create();
		ToolCallback[] tools = sagacity.wrap(new OrderTools());

		System.out.println("Agent task: place an order for 2 x p-1 (3 tool calls, step 3 will fail)\n");

		SagaResult<Void> result = sagacity.saga("place-order-123", () -> {
			for (String tool : List.of("reserveInventory", "createOrder", "scheduleShipment")) {
				for (ToolCallback callback : tools) {
					if (callback.getToolDefinition().name().equals(tool)) {
						callback.call(tool.equals("scheduleShipment") ? "{\"orderId\":\"order-123\"}"
								: "{\"productId\":\"p-1\",\"quantity\":2}");
					}
				}
			}
		});

		System.out.println("\nSaga outcome: " + result.status() + " (cause: " + result.failure().getMessage() + ")");
		System.out.println("\nSide-effect journal (the audit trail):");
		sagacity.journal()
			.entries("place-order-123")
			.forEach(e -> System.out.printf("  #%d %-18s %-20s %s%n", e.seq(), e.toolName(), e.phase(),
					e.payload().isEmpty() ? "" : "· " + e.payload()));
	}

}
