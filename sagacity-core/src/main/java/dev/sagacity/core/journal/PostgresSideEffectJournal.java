package dev.sagacity.core.journal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * Postgres-backed append-only journal with SHA-256 hash chain for tamper evidence.
 * Each entry's hash incorporates the previous entry's hash, creating a verifiable chain.
 *
 * <h2>Concurrency</h2>
 * <p>Appends to one saga are serialized by the {@code (saga_id, seq)} primary key
 * plus a bounded retry. {@code SELECT ... FOR UPDATE} alone is not enough: it locks
 * no rows when the saga has no entries yet, so concurrent first-appends all compute
 * {@code seq = 1} and all but one fail on the key. Under READ COMMITTED the same
 * happens on later appends, because a transaction blocked on the current last row
 * still computes its sequence from the snapshot it already read.
 *
 * <p>A losing append must not be dropped. The side effect it describes has already
 * run, and an EXECUTED row that never reaches the journal is an effect the
 * compensation runner will never undo — so a duplicate-key collision re-reads the
 * tail and retries rather than surfacing as a failure.
 */
public final class PostgresSideEffectJournal implements SideEffectJournal {

	/** SQLSTATE 23505 — unique/primary key violation, in both Postgres and H2. */
	private static final String UNIQUE_VIOLATION = "23505";

	/**
	 * Enough to absorb heavy contention on one saga; a saga with more concurrent
	 * writers than this is pathological and deserves to fail loudly.
	 */
	private static final int MAX_ATTEMPTS = 50;

	private final DataSource dataSource;

	public PostgresSideEffectJournal(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public JournalEntry append(String sagaId, String toolName, Phase phase, String input, String payload) {
		SQLException lastConflict = null;
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			try {
				return tryAppend(sagaId, toolName, phase, input, payload);
			}
			catch (SQLException ex) {
				if (!UNIQUE_VIOLATION.equals(ex.getSQLState())) {
					throw new IllegalStateException("Failed to append journal entry for saga " + sagaId, ex);
				}
				// Another append claimed this seq. Re-read the tail and try again.
				lastConflict = ex;
			}
		}
		throw new IllegalStateException("Failed to append journal entry for saga " + sagaId + " after "
				+ MAX_ATTEMPTS + " attempts under contention", lastConflict);
	}

	private JournalEntry tryAppend(String sagaId, String toolName, Phase phase, String input, String payload)
			throws SQLException {
		try (Connection conn = dataSource.getConnection()) {
			conn.setAutoCommit(false);
			try {
				// Lock and get the last entry for this saga to determine seq and prev hash
				long nextSeq = 1;
				String previousHash = HashChain.zeroHash();

				String selectLast = "SELECT seq, hash FROM side_effect_journal "
						+ "WHERE saga_id = ? ORDER BY seq DESC LIMIT 1 FOR UPDATE";
				try (PreparedStatement ps = conn.prepareStatement(selectLast)) {
					ps.setString(1, sagaId);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							nextSeq = rs.getLong("seq") + 1;
							previousHash = rs.getString("hash");
						}
					}
				}

				// Truncated to what a Postgres TIMESTAMP column can hold, so the value
				// that gets hashed is byte-identical to the value read back later.
				Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);
				String hash = HashChain.computeHash(previousHash, sagaId, nextSeq, toolName,
						phase, input, payload, timestamp);

				String insertSql = "INSERT INTO side_effect_journal "
						+ "(saga_id, seq, tool_name, phase, input, payload, timestamp, hash) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
				try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
					ps.setString(1, sagaId);
					ps.setLong(2, nextSeq);
					ps.setString(3, toolName);
					ps.setString(4, phase.name());
					ps.setString(5, input);
					ps.setString(6, payload);
					ps.setTimestamp(7, Timestamp.from(timestamp));
					ps.setString(8, hash);
					ps.executeUpdate();
				}

				conn.commit();
				return new JournalEntry(sagaId, nextSeq, toolName, phase, input, payload, timestamp, hash);
			} catch (SQLException e) {
				conn.rollback();
				// Rethrown so append() can tell a seq collision from a real failure
				throw e;
			}
		}
	}

	@Override
	public List<JournalEntry> entries(String sagaId) {
		String sql = "SELECT saga_id, seq, tool_name, phase, input, payload, timestamp, hash "
				+ "FROM side_effect_journal WHERE saga_id = ? ORDER BY seq ASC";
		try (Connection conn = dataSource.getConnection();
			 PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, sagaId);
			try (ResultSet rs = ps.executeQuery()) {
				List<JournalEntry> results = new ArrayList<>();
				while (rs.next()) {
					results.add(new JournalEntry(
							rs.getString("saga_id"),
							rs.getLong("seq"),
							rs.getString("tool_name"),
							Phase.valueOf(rs.getString("phase")),
							rs.getString("input"),
							rs.getString("payload"),
							rs.getTimestamp("timestamp").toInstant(),
							rs.getString("hash")
					));
				}
				return results;
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to read journal entries", e);
		}
	}

	/**
	 * Verifies the hash chain integrity for a saga. Returns true if no tampering detected.
	 */
	public boolean verifyChain(String sagaId) {
		return HashChain.verify(entries(sagaId));
	}
}
