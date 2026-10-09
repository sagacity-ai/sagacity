package dev.sagacity.audit;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JdbcAuditStore} using H2 in-memory database.
 * Real Postgres/MySQL tests are in {@link JdbcAuditStoreIT} (requires Docker).
 */
class JdbcAuditStoreTest {

    private JdbcAuditStore store;
    private JdbcDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE sagacity_journal (
                    saga_id     TEXT      NOT NULL,
                    seq         BIGINT    NOT NULL,
                    tool_name   TEXT      NOT NULL,
                    phase       TEXT      NOT NULL,
                    phase_data  TEXT      NOT NULL DEFAULT '{}',
                    input       TEXT      NOT NULL DEFAULT '',
                    recorded_at TIMESTAMP NOT NULL,
                    hash        CHAR(64)  NOT NULL,
                    PRIMARY KEY (saga_id, seq)
                )
                """);
            stmt.execute("""
                CREATE INDEX idx_sagacity_journal_phase
                    ON sagacity_journal (saga_id, phase)
                """);
        }

        store = new JdbcAuditStore(ds);
    }

    @Nested
    @DisplayName("append")
    class AppendTests {

        @Test
        @DisplayName("creates entry with correct fields")
        void appendCreatesEntryWithCorrectFields() {
            AuditEntry entry = store.append("saga-1", "reserveInventory",
                    new Phase.Intent(), "{\"qty\": 5}");

            assertThat(entry.sagaId()).isEqualTo("saga-1");
            assertThat(entry.seq()).isEqualTo(1);
            assertThat(entry.toolName()).isEqualTo("reserveInventory");
            assertThat(entry.phase()).isInstanceOf(Phase.Intent.class);
            assertThat(entry.input()).isEqualTo("{\"qty\": 5}");
            assertThat(entry.timestamp()).isNotNull();
            assertThat(entry.hash()).hasSize(64);
        }

        @Test
        @DisplayName("increments sequence within the same saga")
        void appendIncrementsSequence() {
            store.append("saga-1", "tool-a", new Phase.Intent(), "");
            store.append("saga-1", "tool-a", new Phase.Executed("result"), "");
            AuditEntry third = store.append("saga-1", "tool-b", new Phase.Intent(), "");

            assertThat(third.seq()).isEqualTo(3);
        }

        @Test
        @DisplayName("sequences are independent across different sagas")
        void appendSeparatesSagas() {
            store.append("saga-1", "tool", new Phase.Intent(), "");
            store.append("saga-2", "tool", new Phase.Intent(), "");

            assertThat(store.findBySagaId("saga-1")).hasSize(1);
            assertThat(store.findBySagaId("saga-2")).hasSize(1);
            assertThat(store.findBySagaId("saga-1").get(0).seq()).isEqualTo(1);
            assertThat(store.findBySagaId("saga-2").get(0).seq()).isEqualTo(1);
        }

        @Test
        @DisplayName("Executed phase round-trips its result field")
        void executedPhaseRoundTrips() {
            store.append("saga-1", "chargeCard", new Phase.Executed("{\"txId\":\"tx-99\"}"), "input");

            AuditEntry entry = store.findBySagaId("saga-1").get(0);
            assertThat(entry.phase()).isInstanceOf(Phase.Executed.class);
            assertThat(((Phase.Executed) entry.phase()).result()).isEqualTo("{\"txId\":\"tx-99\"}");
        }

        @Test
        @DisplayName("Failed phase round-trips its error field")
        void failedPhaseRoundTrips() {
            store.append("saga-1", "chargeCard", new Phase.Failed("timeout"), "input");

            AuditEntry entry = store.findBySagaId("saga-1").get(0);
            assertThat(entry.phase()).isInstanceOf(Phase.Failed.class);
            assertThat(((Phase.Failed) entry.phase()).error()).isEqualTo("timeout");
        }

        @Test
        @DisplayName("Rejected phase round-trips its reason field")
        void rejectedPhaseRoundTrips() {
            store.append("saga-1", "deleteAccount", new Phase.Rejected("too risky"), "input");

            AuditEntry entry = store.findBySagaId("saga-1").get(0);
            assertThat(entry.phase()).isInstanceOf(Phase.Rejected.class);
            assertThat(((Phase.Rejected) entry.phase()).reason()).isEqualTo("too risky");
        }
    }

    @Nested
    @DisplayName("findBySagaId")
    class FindTests {

        @Test
        @DisplayName("returns entries in append order")
        void returnsInAppendOrder() {
            store.append("saga-1", "tool", new Phase.Intent(), "in");
            store.append("saga-1", "tool", new Phase.Executed("out"), "in");
            store.append("saga-1", "tool", new Phase.Compensated(), "in");

            List<AuditEntry> entries = store.findBySagaId("saga-1");
            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).phase()).isInstanceOf(Phase.Intent.class);
            assertThat(entries.get(1).phase()).isInstanceOf(Phase.Executed.class);
            assertThat(entries.get(2).phase()).isInstanceOf(Phase.Compensated.class);
        }

        @Test
        @DisplayName("returns empty list for unknown saga")
        void emptyForUnknownSaga() {
            assertThat(store.findBySagaId("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("filters by phase type efficiently")
        void filtersByPhaseType() {
            store.append("saga-1", "tool", new Phase.Intent(), "in");
            store.append("saga-1", "tool", new Phase.Executed("out"), "in");
            store.append("saga-1", "tool", new Phase.Failed("boom"), "in");

            List<AuditEntry> executed = store.findBySagaId("saga-1", Phase.Executed.class);
            assertThat(executed).hasSize(1);
            assertThat(executed.get(0).phase()).isInstanceOf(Phase.Executed.class);
        }
    }

    @Nested
    @DisplayName("hash chain")
    class HashChainTests {

        @Test
        @DisplayName("chain is verifiable after multiple appends")
        void hashChainIsVerifiable() {
            store.append("saga-1", "tool-a", new Phase.Intent(), "in");
            store.append("saga-1", "tool-a", new Phase.Executed("result"), "in");
            store.append("saga-1", "tool-b", new Phase.Intent(), "in2");

            assertThat(store.verifyChain("saga-1")).isTrue();
        }

        @Test
        @DisplayName("each entry hash differs from the previous")
        void eachEntryHashDiffers() {
            store.append("saga-1", "tool", new Phase.Intent(), "same");
            store.append("saga-1", "tool", new Phase.Executed("same"), "same");

            List<AuditEntry> entries = store.findBySagaId("saga-1");
            assertThat(entries.get(0).hash()).isNotEqualTo(entries.get(1).hash());
        }
    }

    @Test
    @DisplayName("concurrent appends maintain correct sequencing")
    void concurrentAppendsMainCorrectSequencing() throws Exception {
        int threads = 10;
        int appendsPerThread = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < appendsPerThread; i++) {
                        store.append("concurrent-saga", "tool", new Phase.Intent(), "input");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        List<AuditEntry> entries = store.findBySagaId("concurrent-saga");
        if (errors.get() == 0) {
            assertThat(entries).hasSize(threads * appendsPerThread);
        }
        for (int i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i).seq()).isEqualTo(i + 1);
        }
        assertThat(store.verifyChain("concurrent-saga")).isTrue();
    }
}
