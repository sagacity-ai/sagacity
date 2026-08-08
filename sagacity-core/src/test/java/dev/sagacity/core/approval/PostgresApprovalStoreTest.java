package dev.sagacity.core.approval;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import dev.sagacity.core.journal.HashChain;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link PostgresApprovalStore} on H2 in PostgreSQL compatibility mode.
 * Real-Postgres coverage lives in {@code PostgresApprovalStoreIT}.
 */
class PostgresApprovalStoreTest {

	private static final String PAYLOAD = "{\"amount\":\"100\",\"to\":\"alice\"}";

	private JdbcDataSource dataSource;

	private PostgresApprovalStore store;

	@BeforeEach
	void setUp() throws Exception {
		this.dataSource = new JdbcDataSource();
		this.dataSource.setURL("jdbc:h2:mem:approval_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
		this.dataSource.setUser("sa");

		try (Connection conn = this.dataSource.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("""
				CREATE TABLE sagacity_approval_request (
				    saga_id      TEXT        NOT NULL,
				    journal_seq  BIGINT      NOT NULL,
				    tool_name    TEXT        NOT NULL,
				    input        TEXT        NOT NULL DEFAULT '',
				    input_hash   CHAR(64)    NOT NULL,
				    created_at   TIMESTAMP   NOT NULL,
				    PRIMARY KEY (saga_id, journal_seq)
				)
			""");
		}
		this.store = new PostgresApprovalStore(this.dataSource);
	}

	private ApprovalRequest request(String sagaId, long seq) {
		return new ApprovalRequest(sagaId, seq, "sendWireTransfer", PAYLOAD, HashChain.sha256(PAYLOAD));
	}

	// ── The point of the class ─────────────────────────────────────────────

	@Test
	void pendingApprovalSurvivesANewStoreOverTheSameDatabase() {
		this.store.save(request("saga-1", 3));

		// A restart is a new object over the same data. The in-memory store
		// loses the request here, stranding the saga forever.
		PostgresApprovalStore afterRestart = new PostgresApprovalStore(this.dataSource);

		assertThat(afterRestart.find("saga-1", 3)).isPresent();
		assertThat(afterRestart.find("saga-1", 3).get().inputHash())
				.isEqualTo(HashChain.sha256(PAYLOAD));
	}

	@Test
	void thePayloadHashRoundTripsExactly() {
		this.store.save(request("saga-1", 3));

		ApprovalRequest read = this.store.find("saga-1", 3).orElseThrow();

		// If the hash does not survive storage, every resume would be refused as
		// a stale approval — a fail-closed bug, but a total one.
		assertThat(read.inputHash()).isEqualTo(HashChain.sha256(PAYLOAD)).hasSize(64);
		assertThat(read.input()).isEqualTo(PAYLOAD);
		assertThat(read.toolName()).isEqualTo("sendWireTransfer");
		assertThat(read.journalSeq()).isEqualTo(3);
	}

	// ── Contract ───────────────────────────────────────────────────────────

	@Test
	void findReturnsEmptyForUnknownKeys() {
		this.store.save(request("saga-1", 3));

		assertThat(this.store.find("saga-1", 99)).isEmpty();
		assertThat(this.store.find("no-such-saga", 3)).isEmpty();
	}

	@Test
	void pendingRequestsListsEverythingAndFiltersBySaga() {
		this.store.save(request("saga-1", 1));
		this.store.save(request("saga-1", 5));
		this.store.save(request("saga-2", 2));

		assertThat(this.store.pendingRequests()).hasSize(3);
		assertThat(this.store.pendingRequests("saga-1"))
				.extracting(ApprovalRequest::journalSeq).containsExactly(1L, 5L);
		assertThat(this.store.pendingRequests("saga-2")).hasSize(1);
		assertThat(this.store.pendingRequests("no-such-saga")).isEmpty();
	}

	@Test
	void removeDeletesOnlyTheTargetedRequest() {
		this.store.save(request("saga-1", 1));
		this.store.save(request("saga-1", 2));
		this.store.save(request("saga-2", 1));

		this.store.remove("saga-1", 1);

		assertThat(this.store.find("saga-1", 1)).isEmpty();
		assertThat(this.store.find("saga-1", 2)).isPresent();
		assertThat(this.store.find("saga-2", 1)).isPresent();
	}

	@Test
	void removeOfSomethingAbsentIsNotAnError() {
		this.store.remove("no-such-saga", 42);
		assertThat(this.store.pendingRequests()).isEmpty();
	}

	@Test
	void savingTheSameKeyTwiceReplacesRatherThanDuplicating() {
		this.store.save(request("saga-1", 3));

		String newPayload = "{\"amount\":\"250\",\"to\":\"bob\"}";
		this.store.save(new ApprovalRequest("saga-1", 3, "sendWireTransfer",
				newPayload, HashChain.sha256(newPayload)));

		// A duplicate would make find() ambiguous about which payload was approved.
		List<ApprovalRequest> all = this.store.pendingRequests("saga-1");
		assertThat(all).hasSize(1);
		assertThat(all.get(0).inputHash()).isEqualTo(HashChain.sha256(newPayload));
	}

	@Test
	void payloadsWithQuotesAndUnicodeSurviveStorage() {
		String awkward = "{\"note\":\"naïve — \\\"quoted\\\", €100\"}";
		this.store.save(new ApprovalRequest("saga-1", 1, "tool", awkward, HashChain.sha256(awkward)));

		ApprovalRequest read = this.store.find("saga-1", 1).orElseThrow();
		assertThat(read.input()).isEqualTo(awkward);
		assertThat(read.inputHash()).isEqualTo(HashChain.sha256(awkward));
	}

}
