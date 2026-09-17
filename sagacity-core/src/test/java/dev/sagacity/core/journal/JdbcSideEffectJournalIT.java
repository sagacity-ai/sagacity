package dev.sagacity.core.journal;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mysql.cj.jdbc.MysqlDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JdbcSideEffectJournal} against real databases.
 *
 * <p>The unit tests in {@link JdbcSideEffectJournalTest} run on H2, which is
 * fast but does not exercise real database behaviour. These tests verify the
 * hash-chain guarantee (tamper evidence) and concurrent-append correctness on
 * actual Postgres and MySQL instances via Testcontainers.
 *
 * <p>Requires Docker. Skipped automatically when no Docker daemon is reachable
 * so the ordinary {@code mvn test} build stays green without it. Run the full
 * suite with {@code mvn verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcSideEffectJournalIT {

	// ── Shared test logic ──────────────────────────────────────────────────

	/**
	 * All database-specific test classes extend this base so the same test
	 * suite runs against every supported database without duplication.
	 */
	abstract static class JournalTests {

		JdbcSideEffectJournal journal;
		DataSource dataSource;

		abstract DataSource createDataSource() throws Exception;

		abstract void createSchema(Connection conn) throws Exception;

		abstract void truncate(Connection conn) throws Exception;

		@BeforeEach
		void setUp() throws Exception {
			this.dataSource = createDataSource();
			try (Connection conn = dataSource.getConnection()) {
				createSchema(conn);
				truncate(conn);
			}
			this.journal = new JdbcSideEffectJournal(dataSource);
		}

		@AfterEach
		void tearDown() throws Exception {
			try (Connection conn = dataSource.getConnection()) {
				truncate(conn);
			}
		}

		// ── The claim the library is sold on ──────────────────────────────

		@Test
		void chainVerifies_afterRoundTripThroughRealDatabase() {
			journal.append("saga-1", "reserveInventory", Phase.INTENT, "{\"item\":\"laptop\"}", "");
			journal.append("saga-1", "reserveInventory", Phase.EXECUTED, "{\"item\":\"laptop\"}", "reserved");
			journal.append("saga-1", "chargeCard", Phase.INTENT, "{\"amount\":100}", "");
			journal.append("saga-1", "chargeCard", Phase.EXECUTED, "{\"amount\":100}", "charged");

			assertThat(journal.verifyChain("saga-1")).isTrue();
		}

		@Test
		void timestampSurvivesRoundTripWithoutBreakingTheChain() {
			JournalEntry written = journal.append("saga-1", "chargeCard", Phase.EXECUTED, "{}", "ok");
			JournalEntry read = journal.entries("saga-1").get(0);

			assertThat(read.timestamp()).isEqualTo(written.timestamp());
			assertThat(read.timestamp()).isEqualTo(read.timestamp().truncatedTo(ChronoUnit.MICROS));
			assertThat(read.hash()).isEqualTo(written.hash());
			assertThat(journal.verifyChain("saga-1")).isTrue();
		}

		@Test
		void chainDetectsUpdateMadeDirectlyInDatabase() throws Exception {
			journal.append("saga-1", "chargeCard", Phase.INTENT, "{\"amount\":100}", "");
			journal.append("saga-1", "chargeCard", Phase.EXECUTED, "{\"amount\":100}", "charged");

			assertThat(journal.verifyChain("saga-1")).isTrue();

			// Simulate tampering — someone with DB access edits the amount
			try (Connection conn = dataSource.getConnection();
				 PreparedStatement ps = conn.prepareStatement(
						 "UPDATE side_effect_journal SET input = ? WHERE saga_id = ? AND seq = ?")) {
				ps.setString(1, "{\"amount\":100000}");
				ps.setString(2, "saga-1");
				ps.setLong(3, 2);
				ps.executeUpdate();
			}

			assertThat(journal.verifyChain("saga-1")).isFalse();
		}

		@Test
		void payloadWithSpecialCharactersStillVerifies() {
			journal.append("saga-1", "runQuery", Phase.EXECUTED, "{\"sql\":\"a|b\"}", "col1|col2");
			journal.append("saga-1", "runQuery", Phase.EXECUTED, "{\"sql\":\"a\"}", "b|col1|col2");

			assertThat(journal.verifyChain("saga-1")).isTrue();
			assertThat(journal.entries("saga-1").get(0).hash())
					.isNotEqualTo(journal.entries("saga-1").get(1).hash());
		}

		@Test
		void unicodePayloadRoundTripsAndVerifies() {
			journal.append("saga-1", "sendReceipt", Phase.EXECUTED,
					"{\"amount\":\"€100\",\"note\":\"naïve — ok\"}", "sent ✓");

			assertThat(journal.entries("saga-1").get(0).input()).contains("€100", "naïve");
			assertThat(journal.verifyChain("saga-1")).isTrue();
		}

		// ── Concurrency ────────────────────────────────────────────────────

		@Test
		void concurrentAppends_produceContiguousVerifiableChain() throws Exception {
			int threads = 8;
			int perThread = 10;
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			CountDownLatch start = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(threads);
			List<Throwable> failures = Collections.synchronizedList(new java.util.ArrayList<>());

			for (int t = 0; t < threads; t++) {
				final int id = t;
				pool.submit(() -> {
					try {
						start.await();
						for (int i = 0; i < perThread; i++) {
							journal.append("concurrent-saga", "tool-" + id, Phase.EXECUTED,
									"{\"i\":" + i + "}", "ok");
						}
					} catch (Throwable ex) {
						failures.add(ex);
					} finally {
						done.countDown();
					}
				});
			}

			start.countDown();
			assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
			pool.shutdownNow();

			assertThat(failures).as("appends must not be dropped under contention").isEmpty();

			List<JournalEntry> entries = journal.entries("concurrent-saga");
			assertThat(entries).hasSize(threads * perThread);
			for (int i = 0; i < entries.size(); i++) {
				assertThat(entries.get(i).seq()).isEqualTo(i + 1L);
			}
			assertThat(journal.verifyChain("concurrent-saga")).isTrue();
		}

		@Test
		void separateSagasDoNotShareSequence() {
			journal.append("saga-a", "tool", Phase.EXECUTED, "{}", "ok");
			journal.append("saga-b", "tool", Phase.EXECUTED, "{}", "ok");
			journal.append("saga-a", "tool", Phase.EXECUTED, "{}", "ok");

			assertThat(journal.entries("saga-a")).extracting(JournalEntry::seq).containsExactly(1L, 2L);
			assertThat(journal.entries("saga-b")).extracting(JournalEntry::seq).containsExactly(1L);
			assertThat(journal.verifyChain("saga-a")).isTrue();
			assertThat(journal.verifyChain("saga-b")).isTrue();
		}

		@Test
		void appendIsDurableAcrossConnections() {
			journal.append("saga-1", "chargeCard", Phase.EXECUTED, "{}", "ok");

			JdbcSideEffectJournal reopened = new JdbcSideEffectJournal(dataSource);
			assertThat(reopened.entries("saga-1")).hasSize(1);
			assertThat(reopened.verifyChain("saga-1")).isTrue();
		}

		@Test
		void emptySagaVerifiesTrivially() {
			assertThat(journal.entries("never-used")).isEmpty();
			assertThat(journal.verifyChain("never-used")).isTrue();
		}
	}

	// ── PostgreSQL ─────────────────────────────────────────────────────────

	@Nested
	@Testcontainers(disabledWithoutDocker = true)
	class PostgresTests extends JournalTests {

		@Container
		@SuppressWarnings("resource")
		private static final PostgreSQLContainer<?> POSTGRES =
				new PostgreSQLContainer<>("postgres:16-alpine");

		@Override
		DataSource createDataSource() {
			PGSimpleDataSource ds = new PGSimpleDataSource();
			ds.setUrl(POSTGRES.getJdbcUrl());
			ds.setUser(POSTGRES.getUsername());
			ds.setPassword(POSTGRES.getPassword());
			return ds;
		}

		@Override
		void createSchema(Connection conn) throws Exception {
			String schema = new String(
					getClass().getResourceAsStream("/sagacity-schema.sql").readAllBytes(),
					StandardCharsets.UTF_8);
			try (Statement stmt = conn.createStatement()) {
				stmt.execute(schema);
			}
		}

		@Override
		void truncate(Connection conn) throws Exception {
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("TRUNCATE side_effect_journal");
			}
		}

		@Test
		void instantNowTruncationMatchesWhatPostgresStores() throws Exception {
			Instant beforeAppend = Instant.now();
			JournalEntry entry = journal.append("saga-1", "tool", Phase.EXECUTED, "{}", "ok");

			assertThat(entry.timestamp()).isBetween(
					beforeAppend.minusSeconds(1), Instant.now().plusSeconds(1));

			try (Connection conn = dataSource.getConnection();
				 Statement stmt = conn.createStatement();
				 var rs = stmt.executeQuery(
						 "SELECT timestamp FROM side_effect_journal WHERE saga_id = 'saga-1' AND seq = 1")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getTimestamp("timestamp").toInstant()).isEqualTo(entry.timestamp());
			}
		}
	}

	// ── MySQL ──────────────────────────────────────────────────────────────

	@Nested
	@Testcontainers(disabledWithoutDocker = true)
	class MySQLTests extends JournalTests {

		@Container
		@SuppressWarnings("resource")
		private static final MySQLContainer<?> MYSQL =
				new MySQLContainer<>("mysql:8.4");

		@Override
		DataSource createDataSource() throws Exception {
			MysqlDataSource ds = new MysqlDataSource();
			ds.setUrl(MYSQL.getJdbcUrl() + "?useSSL=false&allowPublicKeyRetrieval=true");
			ds.setUser(MYSQL.getUsername());
			ds.setPassword(MYSQL.getPassword());
			return ds;
		}

		@Override
		void createSchema(Connection conn) throws Exception {
			try (Statement stmt = conn.createStatement()) {
				// MySQL uses VARCHAR instead of TEXT for indexed/primary key columns
				// and does not support IF NOT EXISTS on indexes in all versions
				stmt.execute("""
					CREATE TABLE IF NOT EXISTS side_effect_journal (
					    saga_id     VARCHAR(255) NOT NULL,
					    seq         BIGINT       NOT NULL,
					    tool_name   VARCHAR(255) NOT NULL,
					    phase       VARCHAR(50)  NOT NULL,
					    input       TEXT         NOT NULL,
					    payload     TEXT         NOT NULL,
					    timestamp   DATETIME(6)  NOT NULL,
					    hash        CHAR(64)     NOT NULL,
					    PRIMARY KEY (saga_id, seq)
					)
				""");
			}
		}

		@Override
		void truncate(Connection conn) throws Exception {
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("DELETE FROM side_effect_journal");
			}
		}
	}
}
