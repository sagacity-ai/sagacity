package dev.sagacity.core.approval;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import dev.sagacity.core.journal.HashChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PostgresApprovalStore} against a real Postgres.
 *
 * <p>These are not redundant with the H2 unit tests. {@code save} takes a
 * different SQL path per database — {@code MERGE} on H2, {@code ON CONFLICT} on
 * Postgres — so the branch that actually runs in production is only covered
 * here.
 *
 * <p>Runs under {@code mvn verify}; skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresApprovalStoreIT {

	private static final String PAYLOAD = "{\"amount\":\"100\",\"to\":\"alice\"}";

	@Container
	@SuppressWarnings("resource")
	private static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine");

	private PGSimpleDataSource dataSource;

	private PostgresApprovalStore store;

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
			stmt.execute("TRUNCATE sagacity_approval_request");
		}
		this.store = new PostgresApprovalStore(this.dataSource);
	}

	private ApprovalRequest request(String sagaId, long seq) {
		return new ApprovalRequest(sagaId, seq, "sendWireTransfer", PAYLOAD, HashChain.sha256(PAYLOAD));
	}

	@Test
	void schemaFileCreatesTheApprovalTable() {
		// Proves sagacity-schema.sql and the auto-configuration's inline DDL have
		// not drifted apart — the store works against the shipped schema.
		this.store.save(request("saga-1", 1));
		assertThat(this.store.find("saga-1", 1)).isPresent();
	}

	@Test
	void savingTheSameKeyTwiceUsesOnConflictAndReplaces() {
		this.store.save(request("saga-1", 3));

		String newPayload = "{\"amount\":\"250\",\"to\":\"bob\"}";
		this.store.save(new ApprovalRequest("saga-1", 3, "sendWireTransfer",
				newPayload, HashChain.sha256(newPayload)));

		List<ApprovalRequest> all = this.store.pendingRequests("saga-1");
		assertThat(all).hasSize(1);
		assertThat(all.get(0).inputHash()).isEqualTo(HashChain.sha256(newPayload));
	}

	@Test
	void pendingApprovalSurvivesAcrossConnectionsAndInstances() {
		this.store.save(request("saga-1", 3));

		PostgresApprovalStore afterRestart = new PostgresApprovalStore(this.dataSource);

		assertThat(afterRestart.find("saga-1", 3)).isPresent();
		assertThat(afterRestart.find("saga-1", 3).get().inputHash())
				.isEqualTo(HashChain.sha256(PAYLOAD));
	}

	@Test
	void payloadHashSurvivesTheCharColumnWithoutPadding() {
		// input_hash is CHAR(64). Postgres blank-pads CHAR on some drivers; a
		// padded hash would never equal a freshly computed one and every resume
		// would be refused as stale.
		this.store.save(request("saga-1", 1));

		String stored = this.store.find("saga-1", 1).orElseThrow().inputHash();
		assertThat(stored).isEqualTo(HashChain.sha256(PAYLOAD));
		assertThat(stored).hasSize(64).doesNotContain(" ");
	}

	@Test
	void unicodeAndQuotedPayloadsRoundTrip() {
		String awkward = "{\"note\":\"naïve — \\\"quoted\\\", €100\"}";
		this.store.save(new ApprovalRequest("saga-1", 1, "tool", awkward, HashChain.sha256(awkward)));

		ApprovalRequest read = this.store.find("saga-1", 1).orElseThrow();
		assertThat(read.input()).isEqualTo(awkward);
		assertThat(read.inputHash()).isEqualTo(HashChain.sha256(awkward));
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
	void listingIsScopedAndOrdered() {
		this.store.save(request("saga-1", 5));
		this.store.save(request("saga-1", 1));
		this.store.save(request("saga-2", 9));

		assertThat(this.store.pendingRequests()).hasSize(3);
		assertThat(this.store.pendingRequests("saga-1"))
				.extracting(ApprovalRequest::journalSeq).containsExactly(1L, 5L);
	}

}
