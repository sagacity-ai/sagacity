package dev.sagacity.autoconfigure;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.springai.Sagacity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the REST surface end to end over a real {@link Sagacity} with real tool
 * beans — no mocking of the thing under test.
 */
class SagacityApprovalControllerTest {

    private static final String TRANSFER_PAYLOAD = "{\"amount\":\"100\",\"to\":\"alice\"}";

    private MockMvc mockMvc;
    private Sagacity sagacity;
    private TransferTools transferTools;

    @BeforeEach
    void setUp() {
        sagacity = Sagacity.create();
        transferTools = new TransferTools();
        ToolCallback[] tools = sagacity.wrap(transferTools);
        mockMvc = MockMvcBuilders.standaloneSetup(new SagacityApprovalController(sagacity)).build();

        sagacity.saga("saga-1", () -> {
            byName(tools, "reserveInventory").call("{\"item\":\"laptop\"}");
            byName(tools, "sendWireTransfer").call(TRANSFER_PAYLOAD);
            return null;
        });
    }

    private long pendingSeq() {
        return sagacity.pendingApprovals("saga-1").get(0).journalSeq();
    }

    // ── Listing ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /sagacity/approvals lists pending requests with payload hash")
    void getApprovals_listsPendingRequests() throws Exception {
        mockMvc.perform(get("/sagacity/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].sagaId").value("saga-1"))
                .andExpect(jsonPath("$[0].toolName").value("sendWireTransfer"))
                .andExpect(jsonPath("$[0].inputHash").isNotEmpty());
    }

    @Test
    @DisplayName("GET /sagacity/approvals/{sagaId} filters by sagaId")
    void getApprovalsForSaga_filtersBySagaId() throws Exception {
        mockMvc.perform(get("/sagacity/approvals/saga-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));

        mockMvc.perform(get("/sagacity/approvals/other-saga"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    // ── Approve / reject ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /sagacity/approve records approver but does not execute tool")
    void approve_recordsApproverButDoesNotExecuteTool() throws Exception {
        mockMvc.perform(post("/sagacity/approve/saga-1/" + pendingSeq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"manager@company.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.approverIdentity").value("manager@company.com"));

        assertThat(transferTools.lastTransferTo).isNull();
    }

    @Test
    @DisplayName("POST /sagacity/approve without approver records 'unknown'")
    void approve_withoutApprover_recordsUnknown() throws Exception {
        mockMvc.perform(post("/sagacity/approve/saga-1/" + pendingSeq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approverIdentity").value("unknown"));
    }

    @Test
    @DisplayName("POST /sagacity/reject compensates prior steps and clears pending request")
    void reject_compensatesPriorStepsAndClearsPending() throws Exception {
        mockMvc.perform(post("/sagacity/reject/saga-1/" + pendingSeq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"manager@company.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false));

        assertThat(transferTools.compensationCount).isEqualTo(1);
        assertThat(sagacity.pendingApprovals("saga-1")).isEmpty();
        assertThat(transferTools.lastTransferTo).isNull();
    }

    // ── Resume ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /sagacity/resume with approved payload executes tool")
    void resume_afterApproval_withApprovedPayload_executesTool() throws Exception {
        long seq = pendingSeq();
        approve(seq);

        mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resumeBody(TRANSFER_PAYLOAD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result").value("\"transfer-ok\""));

        assertThat(transferTools.lastTransferTo).isEqualTo("alice");
        assertThat(transferTools.lastAmount).isEqualTo(100);
    }

    @Test
    @DisplayName("POST /sagacity/resume with changed payload is 409 and tool never runs")
    void resume_withChangedPayload_is409AndToolNeverRuns() throws Exception {
        long seq = pendingSeq();
        approve(seq);

        mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resumeBody("{\"amount\":\"999999\",\"to\":\"mallory\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("COMPENSATED"))
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("payload changed")));

        assertThat(transferTools.lastTransferTo).isNull();
        assertThat(transferTools.lastAmount).isEqualTo(0);
        assertThat(transferTools.compensationCount).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /sagacity/resume without approval is 409 and tool never runs")
    void resume_withoutApproving_is409() throws Exception {
        mockMvc.perform(post("/sagacity/resume/saga-1/" + pendingSeq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resumeBody(TRANSFER_PAYLOAD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("no human approval recorded")));

        assertThat(transferTools.lastTransferTo).isNull();
    }

    @Test
    @DisplayName("POST /sagacity/resume twice executes only once")
    void resume_twice_executesOnlyOnce() throws Exception {
        long seq = pendingSeq();
        approve(seq);

        mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resumeBody(TRANSFER_PAYLOAD)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resumeBody(TRANSFER_PAYLOAD)))
                .andExpect(status().isNotFound());

        assertThat(transferTools.transferCount).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /sagacity/resume for unknown saga is 404")
    void resume_forUnknownSaga_is404() throws Exception {
        mockMvc.perform(post("/sagacity/resume/no-such-saga/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resumeBody(TRANSFER_PAYLOAD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("No pending approval found")));
    }

    @Test
    @DisplayName("POST /sagacity/resume without payload field is 400")
    void resume_withoutPayloadField_is400() throws Exception {
        mockMvc.perform(post("/sagacity/resume/saga-1/" + pendingSeq())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("must contain a 'payload' field")));

        assertThat(transferTools.lastTransferTo).isNull();
    }

    // ── Audit ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /sagacity/audit returns JSON Lines with one entry per journal row")
    void auditExport_returnsJsonLines() throws Exception {
        String body = mockMvc.perform(get("/sagacity/audit/saga-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn().getResponse().getContentAsString();

        String[] lines = body.strip().split("\n");
        assertThat(lines).hasSameSizeAs(sagacity.auditStore().findBySagaId("saga-1"));
        assertThat(lines).allMatch(line -> line.startsWith("{") && line.endsWith("}"));
        assertThat(body).contains("AwaitingApproval");
    }

    @Test
    @DisplayName("GET /sagacity/audit/verify reports unchained journal correctly")
    void auditVerify_reportsUnchainedJournal() throws Exception {
        mockMvc.perform(get("/sagacity/audit/saga-1/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not hash-chained")))
                .andExpect(jsonPath("$.breakAtIndex").value(-1));
    }

    @Test
    @DisplayName("audit export reflects a stale-approval refusal in journal")
    void auditExport_reflectsStaleApprovalRefusal() throws Exception {
        long seq = pendingSeq();
        approve(seq);
        mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resumeBody("{\"amount\":\"999999\",\"to\":\"mallory\"}")))
                .andExpect(status().isConflict());

        String body = mockMvc.perform(get("/sagacity/audit/saga-1"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Rejected").contains("stale-approval");
        assertThat(sagacity.auditStore().findBySagaId("saga-1"))
                .anyMatch(e -> e.phase() instanceof Phase.Approved)
                .anyMatch(e -> e.phase() instanceof Phase.Rejected);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void approve(long seq) throws Exception {
        mockMvc.perform(post("/sagacity/approve/saga-1/" + seq)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"manager@company.com\"}"))
                .andExpect(status().isOk());
    }

    private static String resumeBody(String toolPayload) {
        return "{\"payload\":\"" + toolPayload.replace("\"", "\\\"") + "\"}";
    }

    private static ToolCallback byName(ToolCallback[] callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb.getToolDefinition().name().equals(name)) return cb;
        }
        throw new IllegalArgumentException("no tool: " + name);
    }

    // ── test tool bean ───────────────────────────────────────────────────────

    static class TransferTools {
        String lastTransferTo = null;
        int lastAmount = 0;
        int transferCount = 0;
        int compensationCount = 0;

        @Tool(description = "Reserve inventory item")
        @Compensable(by = "releaseInventory")
        public String reserveInventory(String item) { return "reserved-" + item; }

        @Compensation
        public void releaseInventory(CompensationContext ctx) { compensationCount++; }

        @Tool(description = "Send wire transfer")
        @Compensable(reversibility = Reversibility.IRREVERSIBLE)
        public String sendWireTransfer(String amount, String to) {
            lastAmount = Integer.parseInt(amount);
            lastTransferTo = to;
            transferCount++;
            return "transfer-ok";
        }
    }
}
