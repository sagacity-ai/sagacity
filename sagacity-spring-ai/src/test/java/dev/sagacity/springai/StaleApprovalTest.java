package dev.sagacity.springai;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.approval.ApprovalRequest;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.HashChain;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.saga.SagaStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for stale approval detection.
 *
 * <p>The attack scenario: a human approves tool execution for payload A,
 * but by the time the tool runs the model has re-planned and the live
 * payload is B. Without hash binding, B executes under A's approval.
 * Sagacity should detect this and reject execution, running compensation.
 */
class StaleApprovalTest {

    private Sagacity sagacity;
    private ToolCallback[] tools;
    private TransferTools transferTools;

    @BeforeEach
    void setUp() {
        sagacity = Sagacity.create();
        transferTools = new TransferTools();
        tools = sagacity.wrap(transferTools);
    }

    @Test
    @DisplayName("approval request stores input hash of original payload")
    void approvalRequestStoresInputHash() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        ApprovalRequest request = sagacity.pendingApprovals("saga-1").get(0);
        assertThat(request.inputHash()).isNotBlank();
        assertThat(request.inputHash())
                .isEqualTo(HashChain.sha256("{\"amount\":100,\"to\":\"alice\"}"));
    }

    @Test
    @DisplayName("resumeSaga with matching payload executes the tool")
    void resumeSagaWithMatchingPayloadExecutesTool() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        SagaResult<String> result = sagacity.resumeSaga("saga-1", seq,
                "{\"amount\":100,\"to\":\"alice\"}", byName(tools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(transferTools.lastTransferTo).isEqualTo("alice");
        assertThat(transferTools.lastAmount).isEqualTo(100);
    }

    @Test
    @DisplayName("resumeSaga with tampered payload rejects and compensates")
    void resumeSagaWithTamperedPayloadRejectsAndCompensates() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "reserveInventory").call("{\"item\":\"laptop\"}");
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        SagaResult<String> result = sagacity.resumeSaga("saga-1", seq,
                "{\"amount\":10000,\"to\":\"mallory\"}", byName(tools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(transferTools.lastTransferTo).isNull();
        assertThat(transferTools.compensationCount).isEqualTo(1);

        assertThat(sagacity.auditStore().findBySagaId("saga-1"))
                .anyMatch(e -> e.phase() instanceof Phase.Rejected
                        && ((Phase.Rejected) e.phase()).reason().contains("stale-approval"));
    }

    @Test
    @DisplayName("resumeSaga with tampered payload does not execute the tool")
    void resumeSagaWithTamperedPayloadDoesNotExecuteTool() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        sagacity.resumeSaga("saga-1", seq,
                "{\"amount\":999999,\"to\":\"attacker\"}", byName(tools, "sendWireTransfer"));

        assertThat(transferTools.lastTransferTo).isNull();
        assertThat(transferTools.lastAmount).isEqualTo(0);
    }

    @Test
    @DisplayName("resumeSaga with no pending approval throws IllegalState")
    void resumeSagaWithNoPendingApprovalThrows() {
        assertThatThrownBy(() ->
                sagacity.resumeSaga("no-such-saga", 99, "{\"amount\":100,\"to\":\"alice\"}",
                        byName(tools, "sendWireTransfer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No pending approval found");
    }

    @Test
    @DisplayName("approval request input hash is a 64-char hex SHA-256")
    void approvalRequestInputHashIsSha256() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":50,\"to\":\"bob\"}");
            return null;
        });

        ApprovalRequest req = sagacity.pendingApprovals("saga-1").get(0);
        assertThat(req.inputHash()).hasSize(64);
        assertThat(req.inputHash()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("different payloads produce different hashes")
    void differentPayloadsProduceDifferentHashes() {
        String hash1 = HashChain.sha256("{\"amount\":100,\"to\":\"alice\"}");
        String hash2 = HashChain.sha256("{\"amount\":10000,\"to\":\"mallory\"}");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("same payload always produces the same hash")
    void samePayloadProducesSameHash() {
        String payload = "{\"amount\":100,\"to\":\"alice\"}";
        assertThat(HashChain.sha256(payload)).isEqualTo(HashChain.sha256(payload));
    }

    @Test
    @DisplayName("resumeSaga journals Intent and Executed on success")
    void resumeSagaJournalsIntentAndExecutedOnSuccess() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        sagacity.resumeSaga("saga-1", seq, "{\"amount\":100,\"to\":\"alice\"}",
                byName(tools, "sendWireTransfer"));

        var entries = sagacity.auditStore().findBySagaId("saga-1");
        assertThat(entries).anyMatch(e -> e.phase() instanceof Phase.Intent
                && e.toolName().equals("sendWireTransfer"));
        assertThat(entries).anyMatch(e -> e.phase() instanceof Phase.Executed
                && e.toolName().equals("sendWireTransfer"));
    }

    @Test
    @DisplayName("whitespace variation in payload is detected as stale")
    void whitespaceVariationIsDetectedAsStale() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        SagaResult<String> result = sagacity.resumeSaga("saga-1", seq,
                "{ \"amount\": 100, \"to\": \"alice\" }", byName(tools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(transferTools.lastTransferTo).isNull();
    }

    @Test
    @DisplayName("resumeSaga without approval refuses even when payload matches")
    void resumeSagaWithoutApprovalRefuses() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        // approve() deliberately NOT called

        SagaResult<String> result = sagacity.resumeSaga("saga-1", seq,
                "{\"amount\":100,\"to\":\"alice\"}", byName(tools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(transferTools.lastTransferTo).isNull();
        assertThat(result.failure()).hasMessageContaining("no human approval recorded");
        assertThat(sagacity.auditStore().findBySagaId("saga-1"))
                .anyMatch(e -> e.phase() instanceof Phase.Rejected
                        && ((Phase.Rejected) e.phase()).reason().contains("no approval recorded"));
    }

    @Test
    @DisplayName("resumeSaga after rejection cannot be resurrected")
    void resumeSagaAfterRejectionCannotBeResurrected() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.reject("saga-1", seq, "manager@company.com");

        assertThatThrownBy(() -> sagacity.resumeSaga("saga-1", seq,
                "{\"amount\":100,\"to\":\"alice\"}", byName(tools, "sendWireTransfer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No pending approval found");
        assertThat(transferTools.lastTransferTo).isNull();
    }

    @Test
    @DisplayName("approval carrying no payload hash fails closed")
    void approvalCarryingNoPayloadHashFailsClosed() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "reserveInventory").call("{\"item\":\"laptop\"}");
            return null;
        });

        long seq = 99;
        sagacity.approvalStore().save(new ApprovalRequest("saga-1", seq,
                "sendWireTransfer", "{\"amount\":100,\"to\":\"alice\"}"));
        sagacity.approve("saga-1", seq, "manager@company.com");

        SagaResult<String> result = sagacity.resumeSaga("saga-1", seq,
                "{\"amount\":100,\"to\":\"alice\"}", byName(tools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(transferTools.lastTransferTo).isNull();
        assertThat(result.failure()).hasMessageContaining("no recorded payload hash");
    }

    @Test
    @DisplayName("stale approval compensates exactly once")
    void staleApprovalCompensatesExactlyOnce() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "reserveInventory").call("{\"item\":\"laptop\"}");
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        sagacity.resumeSaga("saga-1", seq,
                "{\"amount\":10000,\"to\":\"mallory\"}", byName(tools, "sendWireTransfer"));

        assertThat(transferTools.compensationCount).isEqualTo(1);
        assertThat(sagacity.auditStore().findBySagaId("saga-1", Phase.Compensated.class)).hasSize(1);
    }

    @Test
    @DisplayName("when approved tool fails, compensates exactly once")
    void whenApprovedToolFailsCompensatesExactlyOnce() {
        FailingTransferTools failing = new FailingTransferTools();
        ToolCallback[] failTools = sagacity.wrap(failing);

        sagacity.saga("saga-2", () -> {
            byName(failTools, "reserveInventory").call("{\"item\":\"laptop\"}");
            byName(failTools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-2").get(0).journalSeq();
        sagacity.approve("saga-2", seq, "manager@company.com");

        SagaResult<String> result = sagacity.resumeSaga("saga-2", seq,
                "{\"amount\":100,\"to\":\"alice\"}", byName(failTools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(failing.compensationCount).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ToolCallback byName(ToolCallback[] callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb.getToolDefinition().name().equals(name)) return cb;
        }
        throw new IllegalArgumentException("no tool: " + name);
    }

    // ── test tool beans ──────────────────────────────────────────────────────

    static class TransferTools {
        String lastTransferTo = null;
        int lastAmount = 0;
        int compensationCount = 0;

        @Tool(description = "Reserve inventory item")
        @Compensable(by = "releaseInventory")
        public String reserveInventory(String item) { return "reserved-" + item; }

        @Compensation
        public void releaseInventory(CompensationContext ctx) { this.compensationCount++; }

        @Tool(description = "Send wire transfer")
        @Compensable(reversibility = Reversibility.IRREVERSIBLE)
        public String sendWireTransfer(String amount, String to) {
            this.lastAmount = Integer.parseInt(amount);
            this.lastTransferTo = to;
            return "transfer-ok";
        }
    }

    static class FailingTransferTools {
        int compensationCount = 0;

        @Tool(description = "Reserve inventory item")
        @Compensable(by = "releaseInventory")
        public String reserveInventory(String item) { return "reserved-" + item; }

        @Compensation
        public void releaseInventory(CompensationContext ctx) { this.compensationCount++; }

        @Tool(description = "Send wire transfer")
        @Compensable(reversibility = Reversibility.IRREVERSIBLE)
        public String sendWireTransfer(String amount, String to) {
            throw new IllegalStateException("payment gateway unreachable");
        }
    }
}
