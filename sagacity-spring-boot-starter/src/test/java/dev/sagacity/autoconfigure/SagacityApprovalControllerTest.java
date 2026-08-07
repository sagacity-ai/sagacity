package dev.sagacity.autoconfigure;

import dev.sagacity.core.Reversibility;
import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.springai.Sagacity;

import org.junit.jupiter.api.BeforeEach;
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
 * beans — no mocking of the thing under test. The endpoints are what an operator
 * touches when money is about to move, so the tests assert on what actually
 * happened to the tool, not only on the response body.
 */
class SagacityApprovalControllerTest {

	private static final String TRANSFER_PAYLOAD = "{\"amount\":\"100\",\"to\":\"alice\"}";

	private MockMvc mockMvc;

	private Sagacity sagacity;

	private TransferTools transferTools;

	@BeforeEach
	void setUp() {
		this.sagacity = Sagacity.create();
		this.transferTools = new TransferTools();
		ToolCallback[] tools = this.sagacity.wrap(this.transferTools);
		this.mockMvc = MockMvcBuilders.standaloneSetup(new SagacityApprovalController(this.sagacity)).build();

		// Run a saga that stops at the approval gate, so every test starts with
		// one pending approval at a known seq.
		this.sagacity.saga("saga-1", () -> {
			byName(tools, "reserveInventory").call("{\"item\":\"laptop\"}");
			byName(tools, "sendWireTransfer").call(TRANSFER_PAYLOAD);
			return null;
		});
	}

	private long pendingSeq() {
		return this.sagacity.pendingApprovals("saga-1").get(0).journalSeq();
	}

	// ── Listing ────────────────────────────────────────────────────────────

	@Test
	void getApprovals_listsPendingRequestsWithTheirPayloadHash() throws Exception {
		this.mockMvc.perform(get("/sagacity/approvals"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
			.andExpect(jsonPath("$[0].sagaId").value("saga-1"))
			.andExpect(jsonPath("$[0].toolName").value("sendWireTransfer"))
			.andExpect(jsonPath("$[0].inputHash").isNotEmpty());
	}

	@Test
	void getApprovalsForSaga_filtersBySagaId() throws Exception {
		this.mockMvc.perform(get("/sagacity/approvals/saga-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));

		this.mockMvc.perform(get("/sagacity/approvals/other-saga"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
	}

	// ── Approve / reject ───────────────────────────────────────────────────

	@Test
	void approve_recordsTheApproverButDoesNotExecuteTheTool() throws Exception {
		this.mockMvc.perform(post("/sagacity/approve/saga-1/" + pendingSeq())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"approver\":\"manager@company.com\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.approved").value(true))
			.andExpect(jsonPath("$.approverIdentity").value("manager@company.com"));

		// Approving is not executing — that is what /resume is for.
		assertThat(this.transferTools.lastTransferTo).isNull();
	}

	@Test
	void approve_withoutApproverInBody_recordsUnknownRatherThanFailing() throws Exception {
		this.mockMvc.perform(post("/sagacity/approve/saga-1/" + pendingSeq())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.approverIdentity").value("unknown"));
	}

	@Test
	void reject_compensatesPriorStepsAndClearsThePendingRequest() throws Exception {
		this.mockMvc.perform(post("/sagacity/reject/saga-1/" + pendingSeq())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"approver\":\"manager@company.com\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.approved").value(false));

		assertThat(this.transferTools.compensationCount).isEqualTo(1);
		assertThat(this.sagacity.pendingApprovals("saga-1")).isEmpty();
		assertThat(this.transferTools.lastTransferTo).isNull();
	}

	// ── Resume ─────────────────────────────────────────────────────────────

	@Test
	void resume_afterApproval_withTheApprovedPayload_executesTheTool() throws Exception {
		long seq = pendingSeq();
		approve(seq);

		this.mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
				.contentType(MediaType.APPLICATION_JSON)
				.content(resumeBody(TRANSFER_PAYLOAD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.result").value("\"transfer-ok\""));

		assertThat(this.transferTools.lastTransferTo).isEqualTo("alice");
		assertThat(this.transferTools.lastAmount).isEqualTo(100);
	}

	@Test
	void resume_withAChangedPayload_is409AndTheToolNeverRuns() throws Exception {
		long seq = pendingSeq();
		approve(seq);

		this.mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
				.contentType(MediaType.APPLICATION_JSON)
				.content(resumeBody("{\"amount\":\"999999\",\"to\":\"mallory\"}")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value("COMPENSATED"))
			.andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("payload changed")));

		assertThat(this.transferTools.lastTransferTo).isNull();
		assertThat(this.transferTools.lastAmount).isEqualTo(0);
		assertThat(this.transferTools.compensationCount).isEqualTo(1);
	}

	@Test
	void resume_withoutApproving_is409AndTheToolNeverRuns() throws Exception {
		// The payload is the one that was gated — only the approval is missing.
		this.mockMvc.perform(post("/sagacity/resume/saga-1/" + pendingSeq())
				.contentType(MediaType.APPLICATION_JSON)
				.content(resumeBody(TRANSFER_PAYLOAD)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value(
					org.hamcrest.Matchers.containsString("no human approval recorded")));

		assertThat(this.transferTools.lastTransferTo).isNull();
	}

	@Test
	void resume_twice_executesOnlyOnce() throws Exception {
		long seq = pendingSeq();
		approve(seq);

		this.mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
				.contentType(MediaType.APPLICATION_JSON)
				.content(resumeBody(TRANSFER_PAYLOAD)))
			.andExpect(status().isOk());

		// The pending request is consumed; a replay must not move money again.
		this.mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
				.contentType(MediaType.APPLICATION_JSON)
				.content(resumeBody(TRANSFER_PAYLOAD)))
			.andExpect(status().isNotFound());

		assertThat(this.transferTools.transferCount).isEqualTo(1);
	}

	@Test
	void resume_forAnUnknownSaga_is404() throws Exception {
		this.mockMvc.perform(post("/sagacity/resume/no-such-saga/99")
				.contentType(MediaType.APPLICATION_JSON)
				.content(resumeBody(TRANSFER_PAYLOAD)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value(
					org.hamcrest.Matchers.containsString("No pending approval found")));
	}

	@Test
	void resume_withoutAPayloadField_is400() throws Exception {
		this.mockMvc.perform(post("/sagacity/resume/saga-1/" + pendingSeq())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value(
					org.hamcrest.Matchers.containsString("must contain a 'payload' field")));

		assertThat(this.transferTools.lastTransferTo).isNull();
	}

	// ── Audit ──────────────────────────────────────────────────────────────

	@Test
	void auditExport_returnsJsonLinesOnePerJournalEntry() throws Exception {
		String body = this.mockMvc.perform(get("/sagacity/audit/saga-1"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
			.andReturn().getResponse().getContentAsString();

		String[] lines = body.strip().split("\n");
		assertThat(lines).hasSameSizeAs(this.sagacity.journal().entries("saga-1"));
		assertThat(lines).allMatch(line -> line.startsWith("{") && line.endsWith("}"));
		assertThat(body).contains("AWAITING_APPROVAL");
	}

	@Test
	void auditVerify_saysTamperEvidenceIsUnavailableOnAnUnchainedJournal() throws Exception {
		// Sagacity.create() uses the in-memory journal, which writes no hashes.
		// That must not be reported the same way as a broken chain — an operator
		// reading "invalid" needs to know whether anything was actually tampered.
		this.mockMvc.perform(get("/sagacity/audit/saga-1/verify"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.valid").value(false))
			.andExpect(jsonPath("$.message").value(
					org.hamcrest.Matchers.containsString("not hash-chained")))
			.andExpect(jsonPath("$.breakAtIndex").value(-1));
	}

	@Test
	void auditExport_reflectsARefusalThatAlreadyHappened() throws Exception {
		long seq = pendingSeq();
		approve(seq);
		this.mockMvc.perform(post("/sagacity/resume/saga-1/" + seq)
				.contentType(MediaType.APPLICATION_JSON)
				.content(resumeBody("{\"amount\":\"999999\",\"to\":\"mallory\"}")))
			.andExpect(status().isConflict());

		String body = this.mockMvc.perform(get("/sagacity/audit/saga-1"))
			.andReturn().getResponse().getContentAsString();

		assertThat(body).contains("REJECTED").contains("stale-approval");
		assertThat(this.sagacity.journal().entries("saga-1"))
			.anyMatch(e -> e.phase() == Phase.APPROVED)
			.anyMatch(e -> e.phase() == Phase.REJECTED);
	}

	// ── Helpers ────────────────────────────────────────────────────────────

	private void approve(long seq) throws Exception {
		this.mockMvc.perform(post("/sagacity/approve/saga-1/" + seq)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"approver\":\"manager@company.com\"}"))
			.andExpect(status().isOk());
	}

	/** Wraps a tool payload as the JSON body {@code {"payload": "..."}}. */
	private static String resumeBody(String toolPayload) {
		return "{\"payload\":\"" + toolPayload.replace("\"", "\\\"") + "\"}";
	}

	private static ToolCallback byName(ToolCallback[] callbacks, String name) {
		for (ToolCallback cb : callbacks) {
			if (cb.getToolDefinition().name().equals(name)) {
				return cb;
			}
		}
		throw new IllegalArgumentException("no tool: " + name);
	}

	// ── Test tool bean ─────────────────────────────────────────────────────

	static class TransferTools {

		String lastTransferTo = null;

		int lastAmount = 0;

		int transferCount = 0;

		int compensationCount = 0;

		@Tool(description = "Reserve inventory item")
		@Compensable(by = "releaseInventory")
		public String reserveInventory(String item) {
			return "reserved-" + item;
		}

		@Compensation
		public void releaseInventory(CompensationContext ctx) {
			this.compensationCount++;
		}

		@Tool(description = "Send wire transfer")
		@Compensable(reversibility = Reversibility.IRREVERSIBLE)
		public String sendWireTransfer(String amount, String to) {
			this.lastAmount = Integer.parseInt(amount);
			this.lastTransferTo = to;
			this.transferCount++;
			return "transfer-ok";
		}

	}

}
