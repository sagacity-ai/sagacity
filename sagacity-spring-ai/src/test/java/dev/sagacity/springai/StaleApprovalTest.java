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
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for stale approval detection.
 *
 * The attack scenario: a human approves tool execution for payload A,
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

    // ── Core stale-approval scenarios ──────────────────────────────────────

    @Test
    void approvalRequest_storesInputHashOfOriginalPayload() {
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
    void resumeSaga_withMatchingPayload_executesTool() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        SagaResult<String> result = sagacity.resumeSaga(
                "saga-1", seq,
                "{\"amount\":100,\"to\":\"alice\"}",   // same payload as approved
                byName(tools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(transferTools.lastTransferTo).isEqualTo("alice");
        assertThat(transferTools.lastAmount).isEqualTo(100);
    }

    @Test
    void resumeSaga_withTamperedPayload_rejectsAndCompensates() {
        // Prior step: reserve inventory (compensatable)
        sagacity.saga("saga-1", () -> {
            byName(tools, "reserveInventory").call("{\"item\":\"laptop\"}");
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        // Resume with a DIFFERENT payload — the stale approval attack
        SagaResult<String> result = sagacity.resumeSaga(
                "saga-1", seq,
                "{\"amount\":10000,\"to\":\"mallory\"}",  // tampered!
                byName(tools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(transferTools.lastTransferTo).isNull(); // tool never ran
        assertThat(transferTools.compensated).isTrue();    // prior steps undone

        // Verify REJECTED phase recorded in journal
        assertThat(sagacity.journal().entries("saga-1"))
                .anyMatch(e -> e.phase() == Phase.REJECTED
                        && e.payload().contains("stale-approval"));
    }

    @Test
    void resumeSaga_withTamperedPayload_doesNotExecuteTool() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        sagacity.resumeSaga(
                "saga-1", seq,
                "{\"amount\":999999,\"to\":\"attacker\"}",
                byName(tools, "sendWireTransfer"));

        // The critical assertion: money never moved
        assertThat(transferTools.lastTransferTo).isNull();
        assertThat(transferTools.lastAmount).isEqualTo(0);
    }

    @Test
    void resumeSaga_withNoPendingApproval_throwsIllegalState() {
        // Try to resume a saga that never had an approval request
        assertThatThrownBy(() ->
                sagacity.resumeSaga("no-such-saga", 99, "{\"amount\":100,\"to\":\"alice\"}",
                        byName(tools, "sendWireTransfer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No pending approval found");
    }

    @Test
    void approvalRequest_inputHashIsNonEmpty() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":50,\"to\":\"bob\"}");
            return null;
        });

        ApprovalRequest req = sagacity.pendingApprovals("saga-1").get(0);
        // Hash should be 64 hex chars (SHA-256)
        assertThat(req.inputHash()).hasSize(64);
        assertThat(req.inputHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void differentPayloads_produceDifferentHashes() {
        String hash1 = HashChain.sha256("{\"amount\":100,\"to\":\"alice\"}");
        String hash2 = HashChain.sha256("{\"amount\":10000,\"to\":\"mallory\"}");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void samePayload_producesSameHash_deterministic() {
        String payload = "{\"amount\":100,\"to\":\"alice\"}";
        assertThat(HashChain.sha256(payload)).isEqualTo(HashChain.sha256(payload));
    }

    @Test
    void resumeSaga_journalsIntentAndExecuted_onSuccess() {
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        sagacity.resumeSaga("saga-1", seq, "{\"amount\":100,\"to\":\"alice\"}",
                byName(tools, "sendWireTransfer"));

        var entries = sagacity.journal().entries("saga-1");
        assertThat(entries).anyMatch(e -> e.phase() == Phase.INTENT
                && e.toolName().equals("sendWireTransfer"));
        assertThat(entries).anyMatch(e -> e.phase() == Phase.EXECUTED
                && e.toolName().equals("sendWireTransfer"));
    }

    @Test
    void whitespaceVariation_inPayload_isDetectedAsStale() {
        // Payload with no spaces approved; payload with spaces submitted at resume
        sagacity.saga("saga-1", () -> {
            byName(tools, "sendWireTransfer").call("{\"amount\":100,\"to\":\"alice\"}");
            return null;
        });

        long seq = sagacity.pendingApprovals("saga-1").get(0).journalSeq();
        sagacity.approve("saga-1", seq, "manager@company.com");

        // Even minor whitespace changes should be rejected (exact payload binding)
        SagaResult<String> result = sagacity.resumeSaga(
                "saga-1", seq,
                "{ \"amount\": 100, \"to\": \"alice\" }",  // whitespace differs
                byName(tools, "sendWireTransfer"));

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(transferTools.lastTransferTo).isNull();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static ToolCallback byName(ToolCallback[] callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb.getToolDefinition().name().equals(name)) return cb;
        }
        throw new IllegalArgumentException("no tool: " + name);
    }

    // ── Test tool beans ────────────────────────────────────────────────────

    static class TransferTools {

        String lastTransferTo = null;
        int lastAmount = 0;
        boolean compensated = false;

        @Tool(description = "Reserve inventory item")
        @Compensable(by = "releaseInventory")
        public String reserveInventory(String item) {
            return "reserved-" + item;
        }

        @Compensation
        public void releaseInventory(CompensationContext ctx) {
            this.compensated = true;
        }

        @Tool(description = "Send wire transfer")
        @Compensable(reversibility = Reversibility.IRREVERSIBLE)
        public String sendWireTransfer(String amount, String to) {
            this.lastAmount = Integer.parseInt(amount);
            this.lastTransferTo = to;
            return "transfer-ok";
        }

    }

}
