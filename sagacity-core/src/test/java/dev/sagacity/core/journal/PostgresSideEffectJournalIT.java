package dev.sagacity.core.journal;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests against a real Postgres, not H2 in compatibility mode.
 *
 * <p>The unit tests run on H2, which agrees with Postgres on most of what this
 * journal does — but the audit story rests on hashes computed in Java matching
 * rows durably stored by Postgres, and only Postgres can prove that. In
 * particular {@code TIMESTAMP} truncation and the real transaction isolation
 * behaviour under concurrent appends are not things H2 can stand in for.
 *
 * <p>Runs under {@code mvn verify}, needs Docker. Skipped automatically when no
 * Docker daemon is reachable so the ordinary build stays green without it.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresSideEffectJournalIT {

	@Container
	@SuppressWarnings("resource")
	private static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine");

	private PostgresSideEffectJournal journal;

	private PGSimpleDataSource dataSource;

	@BeforeEach
	void setUp() throws Exception {
		this.dataSource = new PGSimpleDataSource();
		this.dataSource.setUrl(POSTGRES.getJdbcUrl());
		this.dataSource.setUser(POSTGRES.getUsername());
		this.dataSource.setPassword(POSTGRES.getPassword());

		String schema = new String(getClass().getResourceAsStream("/sagacity-schema.sql").readAllBytes(),
				StandardCharsets.UTF_8);
		try (Connection conn = this.dataSource.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute(schema);
			stmt.execute("TRUNCATE side_effect_journal");
		}

		this.journal = new PostgresSideEffectJournal(this.dataSource);
	}

	@AfterEach
	void tearDown() throws Exception {
		try (Connection conn = this.dataSource.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("TRUNCATE side_effect_journal");
		}
	}

	// ── The claim the library is sold on ───────────────────────────────────

	@Test
	void chainVerifies_afterRoundTripThroughRealPostgres() {
		this.journal.append("saga-1", "reserveInventory", Phase.INTENT, "{\"item\":\"laptop\"}", "");
		this.journal.append("saga-1", "reserveInventory", Phase.EXECUTED, "{\"item\":\"laptop\"}", "reserved");
		this.journal.append("saga-1", "chargeCard", Phase.INTENT, "{\"amount\":100}", "");
		this.journal.append("saga-1", "chargeCard", Phase.EXECUTED, "{\"amount\":100}", "charged");

		assertThat(this.journal.verifyChain("saga-1")).isTrue();
	}

	@Test
	void timestampSurvivesTheRoundTripWithoutBreakingTheChain() {
		JournalEntry written = this.journal.append("saga-1", "chargeCard", Phase.EXECUTED, "{}", "ok");

		JournalEntry read = this.journal.entries("saga-1").get(0);

		// Whatever Postgres gave back must be exactly what was hashed, or
		// verification of a persisted saga would fail for everyone.
		assertThat(read.timestamp()).isEqualTo(written.timestamp());
		assertThat(read.timestamp()).isEqualTo(read.timestamp().truncatedTo(ChronoUnit.MICROS));
		assertThat(read.hash()).isEqualTo(written.hash());
		assertThat(this.journal.verifyChain("saga-1")).isTrue();
	}

	@Test
	void chainDetectsAnUpdateMadeDirectlyInTheDatabase() throws Exception {
		this.journal.append("saga-1", "chargeCard", Phase.INTENT, "{\"amount\":100}", "");
		this.journal.append("saga-1", "chargeCard", Phase.EXECUTED, "{\"amount\":100}", "charged");

		assertThat(this.journal.verifyChain("saga-1")).isTrue();

		// Someone with write access edits the amount but cannot recompute hashes
		try (Connection conn = this.dataSource.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"UPDATE side_effect_journal SET input = ? WHERE saga_id = ? AND seq = ?")) {
			ps.setString(1, "{\"amount\":100000}");
			ps.setString(2, "saga-1");
			ps.setLong(3, 2);
			ps.executeUpdate();
		}

		assertThat(this.journal.verifyChain("saga-1")).isFalse();
	}

	@Test
	void payloadContainingTheOldDelimiterStillVerifies() {
		// Field contents are tool arguments and results — they can hold anything.
		this.journal.append("saga-1", "runQuery", Phase.EXECUTED, "{\"sql\":\"a|b\"}", "col1|col2");
		this.journal.append("saga-1", "runQuery", Phase.EXECUTED, "{\"sql\":\"a\"}", "b|col1|col2");

		assertThat(this.journal.verifyChain("saga-1")).isTrue();
		assertThat(this.journal.entries("saga-1").get(0).hash())
				.isNotEqualTo(this.journal.entries("saga-1").get(1).hash());
	}

	@Test
	void unicodePayloadRoundTripsAndVerifies() {
		this.journal.append("saga-1", "sendReceipt", Phase.EXECUTED,
				"{\"amount\":\"€100\",\"note\":\"naïve — ok\"}", "sent ✓");

		assertThat(this.journal.entries("saga-1").get(0).input()).contains("€100", "naïve");
		assertThat(this.journal.verifyChain("saga-1")).isTrue();
	}

	// ── Concurrency, which H2 cannot stand in for ──────────────────────────

	@Test
	void concurrentAppends_produceAContiguousVerifiableChain() throws Exception {
		int threads = 8;
		int perThread = 10;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++) {
			final int id = t;
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < perThread; i++) {
						this.journal.append("concurrent-saga", "tool-" + id, Phase.EXECUTED,
								"{\"i\":" + i + "}", "ok");
					}
				}
				catch (Exception ignored) {
					// counted by the assertions below
				}
				finally {
					done.countDown();
				}
			});
		}

		start.countDown();
		assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		List<JournalEntry> entries = this.journal.entries("concurrent-saga");
		assertThat(entries).hasSize(threads * perThread);

		// seq must be dense and gap-free, or the chain has a hole in it
		for (int i = 0; i < entries.size(); i++) {
			assertThat(entries.get(i).seq()).isEqualTo(i + 1L);
		}
		assertThat(this.journal.verifyChain("concurrent-saga")).isTrue();
	}

	@Test
	void separateSagasDoNotShareASequence() {
		this.journal.append("saga-a", "tool", Phase.EXECUTED, "{}", "ok");
		this.journal.append("saga-b", "tool", Phase.EXECUTED, "{}", "ok");
		this.journal.append("saga-a", "tool", Phase.EXECUTED, "{}", "ok");

		assertThat(this.journal.entries("saga-a")).extracting(JournalEntry::seq).containsExactly(1L, 2L);
		assertThat(this.journal.entries("saga-b")).extracting(JournalEntry::seq).containsExactly(1L);
		assertThat(this.journal.verifyChain("saga-a")).isTrue();
		assertThat(this.journal.verifyChain("saga-b")).isTrue();
	}

	@Test
	void entriesAreReturnedInSequenceOrder() {
		for (int i = 0; i < 25; i++) {
			this.journal.append("saga-1", "tool", Phase.EXECUTED, "{\"i\":" + i + "}", "ok");
		}

		List<JournalEntry> entries = this.journal.entries("saga-1");
		assertThat(entries).extracting(JournalEntry::seq).isSorted();
		assertThat(entries).hasSize(25);
	}

	@Test
	void appendIsDurableAcrossConnections() {
		this.journal.append("saga-1", "chargeCard", Phase.EXECUTED, "{}", "ok");

		// A fresh journal over a fresh pool sees the committed row
		PostgresSideEffectJournal reopened = new PostgresSideEffectJournal(this.dataSource);
		assertThat(reopened.entries("saga-1")).hasSize(1);
		assertThat(reopened.verifyChain("saga-1")).isTrue();
	}

	@Test
	void emptySagaVerifiesTrivially() {
		assertThat(this.journal.entries("never-used")).isEmpty();
		assertThat(this.journal.verifyChain("never-used")).isTrue();
	}

	@Test
	void instantNowTruncationMatchesWhatPostgresStores() throws Exception {
		// Guards the assumption behind HashChain.canonicalTimestamp: if a JDK or
		// platform ever hands back finer precision than Postgres keeps, the hash
		// would be computed over a value the database cannot hold.
		Instant beforeAppend = Instant.now();
		JournalEntry entry = this.journal.append("saga-1", "tool", Phase.EXECUTED, "{}", "ok");

		assertThat(entry.timestamp()).isBetween(beforeAppend.minusSeconds(1), Instant.now().plusSeconds(1));

		try (Connection conn = this.dataSource.getConnection();
				Statement stmt = conn.createStatement();
				var rs = stmt.executeQuery(
						"SELECT timestamp FROM side_effect_journal WHERE saga_id = 'saga-1' AND seq = 1")) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getTimestamp("timestamp").toInstant()).isEqualTo(entry.timestamp());
		}
	}

}
