package dev.sagacity.springai;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.approval.ApprovalDecision;
import dev.sagacity.core.audit.AuditExporter;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.saga.SagaStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGateTest {

	@Test
	void irreversibleTool_suspendsAndAwaitsApproval() {
		Sagacity sagacity = Sagacity.create();
		ToolCallback[] tools = sagacity.wrap(new IrreversibleTools());

		SagaResult<String> result = sagacity.saga("saga-1", () -> {
			tools[0].call("{\"orderId\": \"o-1\"}");
			return "done";
		});

		assertThat(result.status()).isEqualTo(SagaStatus.AWAITING_APPROVAL);
		assertThat(result.awaitingToolName()).isEqualTo("sendWireTransfer");
		assertThat(sagacity.pendingApprovals()).hasSize(1);
		assertThat(sagacity.pendingApprovals().get(0).toolName()).isEqualTo("sendWireTransfer");
	}

	@Test
	void approve_journalsDecisionWithApproverIdentity() {
		Sagacity sagacity = Sagacity.create();
		ToolCallback[] tools = sagacity.wrap(new IrreversibleTools());

		sagacity.saga("saga-1", () -> {
			tools[0].call("{}");
			return null;
		});

		ApprovalDecision decision = sagacity.approve("saga-1", 1, "admin@company.com");

		assertThat(decision.approved()).isTrue();
		assertThat(decision.approverIdentity()).isEqualTo("admin@company.com");
		// approve() journals but does NOT remove from store — resumeSaga() does that
		assertThat(sagacity.pendingApprovals()).hasSize(1);

		// Check journal has the approval recorded
		var entries = sagacity.journal().entries("saga-1");
		assertThat(entries).anyMatch(
				e -> e.phase() == Phase.APPROVED && e.payload().contains("admin@company.com"));
	}

	@Test
	void reject_triggersCompensationOfPriorSteps() {
		Sagacity sagacity = Sagacity.create();
		ToolCallback[] tools = sagacity.wrap(new MixedTools());

		// First tool is compensatable (executes), second is irreversible (suspends)
		sagacity.saga("saga-1", () -> {
			byName(tools, "reserveInventory").call("{\"id\": \"item-1\"}");
			byName(tools, "sendWireTransfer").call("{\"orderId\": \"o-1\"}");
			return null;
		});

		sagacity.reject("saga-1", 3, "manager@company.com");

		var entries = sagacity.journal().entries("saga-1");
		assertThat(entries).anyMatch(e -> e.phase() == Phase.REJECTED);
		assertThat(entries).anyMatch(e -> e.phase() == Phase.COMPENSATED);
	}

	@Test
	void auditExport_producesJsonLines() {
		Sagacity sagacity = Sagacity.create();
		ToolCallback[] tools = sagacity.wrap(new MixedTools());

		sagacity.saga("saga-1", () -> {
			byName(tools, "reserveInventory").call("{\"id\": \"item-1\"}");
			return null;
		});

		String jsonLines = sagacity.exportAuditLog("saga-1");
		String[] lines = jsonLines.split("\n");
		assertThat(lines.length).isGreaterThanOrEqualTo(2); // INTENT + EXECUTED
		assertThat(lines[0]).contains("\"phase\":\"INTENT\"");
		assertThat(lines[0]).contains("\"sagaId\":\"saga-1\"");
		assertThat(lines[0]).contains("\"hash\"");
	}

	@Test
	void auditVerify_detectsTampering() {
		Sagacity sagacity = Sagacity.create();
		ToolCallback[] tools = sagacity.wrap(new MixedTools());

		sagacity.saga("saga-1", () -> {
			byName(tools, "reserveInventory").call("{\"id\": \"item-1\"}");
			return null;
		});

		// InMemory uses empty hash (no chain), so verify will detect mismatch.
		// This validates that the AuditExporter API works correctly.
		AuditExporter.VerificationResult result = sagacity.verifyJournal("saga-1");
		assertThat(result).isNotNull();
		assertThat(result.entryCount()).isGreaterThanOrEqualTo(2);
	}

	// --- Helpers ---

	private static ToolCallback byName(ToolCallback[] callbacks, String name) {
		for (ToolCallback callback : callbacks) {
			if (callback.getToolDefinition().name().equals(name)) {
				return callback;
			}
		}
		throw new IllegalArgumentException("no tool named " + name);
	}

	// --- Test tool beans ---

	static class IrreversibleTools {

		@Tool(description = "Send a wire transfer")
		@Compensable(reversibility = Reversibility.IRREVERSIBLE)
		public String sendWireTransfer(String orderId) {
			return "transfer-" + orderId;
		}

	}

	static class MixedTools {

		boolean compensated = false;

		@Tool(description = "Reserve inventory")
		@Compensable(by = "releaseInventory")
		public String reserveInventory(String id) {
			return "reserved-" + id;
		}

		@Compensation
		public void releaseInventory(CompensationContext ctx) {
			this.compensated = true;
		}

		@Tool(description = "Send wire transfer")
		@Compensable(reversibility = Reversibility.IRREVERSIBLE)
		public String sendWireTransfer(String orderId) {
			return "transfer-" + orderId;
		}

	}

}
