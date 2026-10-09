package dev.sagacity.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.sagacity.control.ApprovalStore;
import dev.sagacity.audit.AuditStore;
import dev.sagacity.audit.Phase;
import dev.sagacity.mcp.tools.AuditTool;
import dev.sagacity.mcp.tools.ApprovalTool;
import dev.sagacity.mcp.tools.CompensationTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Sagacity MCP Server tools against a real Postgres instance.
 * Requires Docker. Skipped automatically when no Docker daemon is reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SagacityMcpServerIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AuditTool auditTool;
    @Autowired ApprovalTool approvalTool;
    @Autowired CompensationTool compensationTool;
    @Autowired AuditStore auditStore;
    @Autowired ApprovalStore approvalStore;
    @LocalServerPort int port;

    @Nested
    @DisplayName("AuditTool")
    class AuditToolTests {

        @Test
        @DisplayName("logToolCall appends Intent entry to journal")
        void logToolCallAppendsIntentEntry() {
            String sagaId = "saga-audit-1";

            String result = auditTool.logToolCall(sagaId, "chargeCard", "{\"amount\":100}", "Intent", "");

            assertThat(result).contains("logged").contains("sagaId=" + sagaId).contains("phase=Intent");
            assertThat(auditStore.findBySagaId(sagaId)).hasSize(1);
            assertThat(auditStore.findBySagaId(sagaId).get(0).phase()).isInstanceOf(Phase.Intent.class);
        }

        @Test
        @DisplayName("logToolCall appends Executed entry after Intent")
        void logToolCallAppendsExecutedEntry() {
            String sagaId = "saga-audit-2";

            auditTool.logToolCall(sagaId, "chargeCard", "{\"amount\":100}", "Intent", "");
            auditTool.logToolCall(sagaId, "chargeCard", "{\"txId\":\"tx-123\"}", "Executed", "tx-123");

            var entries = auditStore.findBySagaId(sagaId);
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).phase()).isInstanceOf(Phase.Intent.class);
            assertThat(entries.get(1).phase()).isInstanceOf(Phase.Executed.class);
        }

        @Test
        @DisplayName("logToolCall returns error for unknown phase")
        void logToolCallReturnsErrorForUnknownPhase() {
            String result = auditTool.logToolCall("saga-x", "tool", "input", "BOGUS_PHASE", "");

            assertThat(result).startsWith("error: unknown phase");
            assertThat(auditStore.findBySagaId("saga-x")).isEmpty();
        }
    }

    @Nested
    @DisplayName("ApprovalTool")
    class ApprovalToolTests {

        @Test
        @DisplayName("requestApproval saves pending request and journals AwaitingApproval")
        void requestApprovalSavesPendingRequest() {
            String sagaId = "saga-approval-1";
            auditTool.logToolCall(sagaId, "deleteAccount", "{\"userId\":\"u-42\"}", "Intent", "");

            String result = approvalTool.requestApproval(sagaId, 1L, "deleteAccount", "{\"userId\":\"u-42\"}");

            assertThat(result).contains("approval_pending").contains(sagaId);
            assertThat(approvalStore.pendingRequests(sagaId)).hasSize(1);
            assertThat(auditStore.findBySagaId(sagaId))
                    .anyMatch(e -> e.phase() instanceof Phase.AwaitingApproval);
        }

        @Test
        @DisplayName("checkApprovalStatus returns PENDING for outstanding request")
        void checkApprovalStatusReturnsPending() {
            String sagaId = "saga-approval-2";
            auditTool.logToolCall(sagaId, "sendEmail", "{\"to\":\"boss@corp.com\"}", "Intent", "");
            approvalTool.requestApproval(sagaId, 1L, "sendEmail", "{\"to\":\"boss@corp.com\"}");

            String status = approvalTool.checkApprovalStatus(sagaId, 1L);
            assertThat(status).startsWith("PENDING");
        }

        @Test
        @DisplayName("checkApprovalStatus returns NOT_FOUND when no request exists")
        void checkApprovalStatusReturnsNotFound() {
            String status = approvalTool.checkApprovalStatus("nonexistent-saga", 99L);
            assertThat(status).startsWith("NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("CompensationTool")
    class CompensationToolTests {

        @Test
        @DisplayName("compensate returns no-Executed-steps message when saga has only Intent")
        void compensateReportsNoExecutedSteps() {
            String sagaId = "saga-comp-1";
            auditTool.logToolCall(sagaId, "reserveRoom", "{\"roomId\":\"r-1\"}", "Intent", "");

            String result = compensationTool.compensate(sagaId);
            assertThat(result).contains("No Executed steps");
        }

        @Test
        @DisplayName("compensate returns error for unknown sagaId")
        void compensateReturnsErrorForUnknownSaga() {
            String result = compensationTool.compensate("nonexistent-saga-xyz");
            assertThat(result).startsWith("error: no journal entries found");
        }

        @Test
        @DisplayName("listEntries returns all entries in order")
        void listEntriesReturnsAllEntries() {
            String sagaId = "saga-list-1";
            auditTool.logToolCall(sagaId, "bookFlight", "{\"dest\":\"NYC\"}", "Intent", "");
            auditTool.logToolCall(sagaId, "bookFlight", "{\"flightId\":\"f-99\"}", "Executed", "f-99");

            String result = compensationTool.listEntries(sagaId);
            assertThat(result).contains("2 total")
                    .contains("phase=Intent")
                    .contains("phase=Executed");
        }

        @Test
        @DisplayName("listEntries returns not-found for unknown saga")
        void listEntriesReturnsNotFoundForUnknownSaga() {
            String result = compensationTool.listEntries("unknown-saga-abc");
            assertThat(result).contains("no entries found");
        }
    }
}
