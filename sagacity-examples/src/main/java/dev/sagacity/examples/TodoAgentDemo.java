package dev.sagacity.examples;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.springai.SagaResult;
import dev.sagacity.springai.Sagacity;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

/**
 * A Todo-list agent demo. Shows three scenarios:
 *
 * 1. Happy path: create + assign + notify (all succeed)
 * 2. Failure path: create + assign succeed, notification fails → undo in reverse
 * 3. Approval path: IRREVERSIBLE tool (email) suspends for human approval
 *
 * Run with: mvn -pl sagacity-examples exec:java -Dexec.mainClass="dev.sagacity.examples.TodoAgentDemo"
 */
public final class TodoAgentDemo {

	// ──────────────────────────────────────────────────────────────────────
	// Simulated database
	// ──────────────────────────────────────────────────────────────────────

	private static final Map<String, Map<String, String>> todoDb = new HashMap<>();

	// ──────────────────────────────────────────────────────────────────────
	// Tool definitions with compensation
	// ──────────────────────────────────────────────────────────────────────

	static class TodoTools {

		@Tool(description = "Create a new todo item")
		@Compensable(by = "deleteTodo")
		public String createTodo(String title, String priority) {
			String id = "todo-" + UUID.randomUUID().toString().substring(0, 4);
			Map<String, String> todo = new HashMap<>();
			todo.put("title", title);
			todo.put("priority", priority);
			todo.put("status", "open");
			todoDb.put(id, todo);
			System.out.println("  [tool] created " + id + " — \"" + title + "\" (" + priority + ")");
			return id;
		}

		@Compensation
		public void deleteTodo(CompensationContext ctx) {
			String todoId = ctx.result().replace("\"", "");
			todoDb.remove(todoId);
			System.out.println("  [undo] deleted " + todoId);
		}

		@Tool(description = "Assign the todo to a team member")
		@Compensable(by = "unassignTodo")
		public String assignTodo(String todoId, String assignee) {
			Map<String, String> todo = todoDb.get(todoId);
			if (todo != null) {
				todo.put("assignee", assignee);
			}
			System.out.println("  [tool] assigned " + todoId + " → " + assignee);
			return "assigned " + todoId + " to " + assignee;
		}

		@Compensation
		public void unassignTodo(CompensationContext ctx) {
			// Extract todoId from the original input JSON
			String input = ctx.input();
			String todoId = input.replaceAll(".*\"todoId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
			Map<String, String> todo = todoDb.get(todoId);
			if (todo != null) {
				todo.remove("assignee");
			}
			System.out.println("  [undo] unassigned " + todoId);
		}

		@Tool(description = "Notify the assignee about the todo via email")
		@Compensable(by = "noOp", reversibility = Reversibility.COMPENSATABLE)
		public String notifyAssignee(String todoId, String email) {
			// Simulate email failure
			System.out.println("  [tool] sending email to " + email + "...");
			throw new RuntimeException("SMTP server unavailable");
		}

		@Compensation
		public void noOp(CompensationContext ctx) {
			// Can't unsend an email, but this is the best-effort handler
			System.out.println("  [undo] (email was never sent — no action needed)");
		}

	}

	// ──────────────────────────────────────────────────────────────────────
	// Demo runner
	// ──────────────────────────────────────────────────────────────────────

	public static void main(String[] args) {
		Sagacity sagacity = Sagacity.create();
		ToolCallback[] tools = sagacity.wrap(new TodoTools());

		System.out.println("╔══════════════════════════════════════════════════════════════╗");
		System.out.println("║  Sagacity Demo: Todo Agent with Compensation                ║");
		System.out.println("╚══════════════════════════════════════════════════════════════╝");
		System.out.println();

		// ── Scenario: Agent creates a todo, assigns it, tries to notify ──
		System.out.println("Scenario: Create todo → Assign → Notify (email fails)");
		System.out.println("─────────────────────────────────────────────────────");
		System.out.println();

		SagaResult<Void> result = sagacity.saga("todo-saga-1", () -> {
			String todoId = null;

			for (ToolCallback callback : tools) {
				if (callback.getToolDefinition().name().equals("createTodo")) {
					todoId = callback.call("{\"title\":\"Fix login bug\",\"priority\":\"high\"}");
					todoId = todoId.replace("\"", "");
				}
			}

			for (ToolCallback callback : tools) {
				if (callback.getToolDefinition().name().equals("assignTodo")) {
					callback.call("{\"todoId\":\"" + todoId + "\",\"assignee\":\"alice\"}");
				}
			}

			for (ToolCallback callback : tools) {
				if (callback.getToolDefinition().name().equals("notifyAssignee")) {
					callback.call("{\"todoId\":\"" + todoId + "\",\"email\":\"alice@team.com\"}");
				}
			}
		});

		System.out.println();
		System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
		System.out.println("Saga outcome: " + result.status());
		System.out.println("Cause: " + result.failure().getMessage());
		System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
		System.out.println();
		System.out.println("Database after compensation: " + (todoDb.isEmpty() ? "EMPTY (clean!)" : todoDb));
		System.out.println();

		// ── Journal ──
		System.out.println("Audit trail:");
		System.out.println("┌─────┬──────────────────┬─────────────────────┬──────────────────────────────┐");
		System.out.printf("│ %3s │ %-16s │ %-19s │ %-28s │%n", "seq", "tool", "phase", "payload");
		System.out.println("├─────┼──────────────────┼─────────────────────┼──────────────────────────────┤");
		sagacity.journal().entries("todo-saga-1").forEach(e -> System.out.printf("│ %3d │ %-16s │ %-19s │ %-28s │%n",
				e.seq(), truncate(e.toolName(), 16), e.phase(), truncate(e.payload(), 28)));
		System.out.println("└─────┴──────────────────┴─────────────────────┴──────────────────────────────┘");
	}

	private static String truncate(String s, int max) {
		if (s == null || s.isEmpty())
			return "";
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

}
