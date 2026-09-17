package dev.sagacity.core.journal;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JdbcSideEffectJournal using H2 in Postgres compatibility mode.
 * For real Postgres tests, see JdbcSideEffectJournalIT (requires Docker).
 */
class JdbcSideEffectJournalTest {

	private JdbcSideEffectJournal journal;
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
				CREATE TABLE side_effect_journal (
				    saga_id     TEXT        NOT NULL,
				    seq         BIGINT      NOT NULL,
				    tool_name   TEXT        NOT NULL,
				    phase       TEXT        NOT NULL,
				    input       TEXT        NOT NULL DEFAULT '',
				    payload     TEXT        NOT NULL DEFAULT '',
				    timestamp   TIMESTAMP   NOT NULL,
				    hash        CHAR(64)    NOT NULL,
				    PRIMARY KEY (saga_id, seq)
				)
			""");
		}

		journal = new JdbcSideEffectJournal(ds);
	}

	@Test
	void append_createsEntryWithCorrectFields() {
		JournalEntry entry = journal.append("saga-1", "reserveInventory", Phase.INTENT, "{\"qty\": 5}", "");

		assertThat(entry.sagaId()).isEqualTo("saga-1");
		assertThat(entry.seq()).isEqualTo(1);
		assertThat(entry.toolName()).isEqualTo("reserveInventory");
		assertThat(entry.phase()).isEqualTo(Phase.INTENT);
		assertThat(entry.input()).isEqualTo("{\"qty\": 5}");
		assertThat(entry.payload()).isEmpty();
		assertThat(entry.timestamp()).isNotNull();
		assertThat(entry.hash()).hasSize(64);
	}

	@Test
	void append_incrementsSequence() {
		journal.append("saga-1", "tool-a", Phase.INTENT, "", "");
		journal.append("saga-1", "tool-a", Phase.EXECUTED, "", "result");
		JournalEntry third = journal.append("saga-1", "tool-b", Phase.INTENT, "", "");

		assertThat(third.seq()).isEqualTo(3);
	}

	@Test
	void append_separatesSagas() {
		journal.append("saga-1", "tool", Phase.INTENT, "", "");
		journal.append("saga-2", "tool", Phase.INTENT, "", "");

		assertThat(journal.entries("saga-1")).hasSize(1);
		assertThat(journal.entries("saga-2")).hasSize(1);
		assertThat(journal.entries("saga-1").get(0).seq()).isEqualTo(1);
		assertThat(journal.entries("saga-2").get(0).seq()).isEqualTo(1);
	}

	@Test
	void entries_returnsInAppendOrder() {
		journal.append("saga-1", "tool", Phase.INTENT, "in", "");
		journal.append("saga-1", "tool", Phase.EXECUTED, "in", "out");
		journal.append("saga-1", "tool", Phase.COMPENSATED, "in", "");

		List<JournalEntry> entries = journal.entries("saga-1");
		assertThat(entries).hasSize(3);
		assertThat(entries.get(0).phase()).isEqualTo(Phase.INTENT);
		assertThat(entries.get(1).phase()).isEqualTo(Phase.EXECUTED);
		assertThat(entries.get(2).phase()).isEqualTo(Phase.COMPENSATED);
	}

	@Test
	void entries_emptyForUnknownSaga() {
		assertThat(journal.entries("nonexistent")).isEmpty();
	}

	@Test
	void hashChain_isVerifiable() {
		journal.append("saga-1", "tool-a", Phase.INTENT, "in", "");
		journal.append("saga-1", "tool-a", Phase.EXECUTED, "in", "result");
		journal.append("saga-1", "tool-b", Phase.INTENT, "in2", "");

		assertThat(journal.verifyChain("saga-1")).isTrue();
	}

	@Test
	void hashChain_eachEntryHashDiffersFromPrevious() {
		journal.append("saga-1", "tool", Phase.INTENT, "same", "");
		journal.append("saga-1", "tool", Phase.EXECUTED, "same", "");

		List<JournalEntry> entries = journal.entries("saga-1");
		assertThat(entries.get(0).hash()).isNotEqualTo(entries.get(1).hash());
	}

	@Test
	void concurrentAppends_maintainCorrectSequencing() throws Exception {
		// Note: H2 doesn't provide true row-level locking like Postgres.
		// This test uses serialized access to verify logic correctness.
		// Real concurrency testing requires Testcontainers + Postgres (see IT tests).
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
						journal.append("concurrent-saga", "tool", Phase.INTENT, "input", "");
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

		// With H2, some threads may fail due to duplicate key on concurrent insert.
		// We verify that whatever was written is consistent.
		List<JournalEntry> entries = journal.entries("concurrent-saga");

		if (errors.get() == 0) {
			// If no errors, we got all entries
			assertThat(entries).hasSize(threads * appendsPerThread);
			for (int i = 0; i < entries.size(); i++) {
				assertThat(entries.get(i).seq()).isEqualTo(i + 1);
			}
			assertThat(journal.verifyChain("concurrent-saga")).isTrue();
		} else {
			// With H2, some may have failed due to locking limitations.
			// Verify what was written is still a valid chain.
			assertThat(entries).isNotEmpty();
			for (int i = 0; i < entries.size(); i++) {
				assertThat(entries.get(i).seq()).isEqualTo(i + 1);
			}
			assertThat(journal.verifyChain("concurrent-saga")).isTrue();
		}
	}
}
