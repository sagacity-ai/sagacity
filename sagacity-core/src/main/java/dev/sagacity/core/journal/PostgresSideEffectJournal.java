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
 * <p>Thread-safe: uses SELECT FOR UPDATE to serialize appends per saga, ensuring
 * correct sequence numbering and hash chaining under concurrency.
 */
public final class PostgresSideEffectJournal implements SideEffectJournal {

	private final DataSource dataSource;

	public PostgresSideEffectJournal(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public JournalEntry append(String sagaId, String toolName, Phase phase, String input, String payload) {
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
			} catch (Exception e) {
				conn.rollback();
				throw e;
			}
		} catch (SQLException e) {
			throw new RuntimeException("Failed to append journal entry", e);
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
