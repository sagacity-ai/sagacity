package dev.sagacity.core.journal;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
 * JDBC-backed append-only journal with SHA-256 hash chain for tamper evidence.
 * Works with any JDBC-compatible database: PostgreSQL, MySQL, MariaDB, Oracle,
 * H2, SQLite.
 *
 * <h2>Concurrency</h2>
 * <p>Appends use optimistic concurrency — no vendor-specific locking syntax.
 * Each append reads the current tail (plain SELECT), computes the next seq,
 * and INSERTs. If two threads race on the same saga both see the same tail seq
 * and both try to INSERT the same next seq; the unique primary key on
 * {@code (saga_id, seq)} lets exactly one win. The loser catches the
 * duplicate-key violation, re-reads the tail, and retries. This is correct for
 * all databases and handles the empty-saga case that {@code SELECT FOR UPDATE}
 * cannot — there are no rows to lock when a saga has no entries yet.
 *
 * <p>A losing append is never dropped. The side effect it describes has already
 * run; an EXECUTED row that never reaches the journal is an effect the
 * compensation runner will never undo. The retry loop guarantees every
 * append eventually lands.
 *
 * <h2>Timestamp precision</h2>
 * <p>Timestamps are truncated to microseconds before hashing and storage so
 * the value hashed in Java is byte-identical to the value read back from the
 * database. MySQL's {@code TIMESTAMP} column stores microseconds; PostgreSQL's
 * {@code TIMESTAMP} stores microseconds; both agree with
 * {@code ChronoUnit.MICROS} truncation.
 */
public final class JdbcSideEffectJournal implements SideEffectJournal {

	/** SQLSTATE 23505 — unique/primary key violation (PostgreSQL, H2). */
	private static final String UNIQUE_VIOLATION_23505 = "23505";

	/** SQLSTATE 23000 — integrity constraint violation (MySQL, MariaDB, Oracle). */
	private static final String UNIQUE_VIOLATION_23000 = "23000";

	/**
	 * Enough to absorb heavy contention on one saga. A saga with more concurrent
	 * writers than this is pathological and deserves to fail loudly.
	 */
	private static final int MAX_ATTEMPTS = 50;

	private final DataSource dataSource;

	/** Database-specific SQL to select the last journal entry for a saga. */
	private final String selectLastSql;

	public JdbcSideEffectJournal(DataSource dataSource) {
		this.dataSource = dataSource;
		this.selectLastSql = buildSelectLastSql(dataSource);
	}

	/**
	 * Detect the database product at construction time and return the appropriate
	 * SQL for reading the last journal entry. All variants are standard SQL apart
	 * from the row-limit clause, which every major database spells differently.
	 *
	 * <ul>
	 *   <li>PostgreSQL, MySQL, MariaDB, H2, SQLite — {@code LIMIT 1}
	 *   <li>Oracle — {@code FETCH FIRST 1 ROWS ONLY}
	 * </ul>
	 */
	private static String buildSelectLastSql(DataSource dataSource) {
		String base = "SELECT seq, hash FROM side_effect_journal "
				+ "WHERE saga_id = ? ORDER BY seq DESC ";
		try (Connection conn = dataSource.getConnection()) {
			DatabaseMetaData meta = conn.getMetaData();
			String product = meta.getDatabaseProductName();
			if (product != null && product.toLowerCase().contains("oracle")) {
				return base + "FETCH FIRST 1 ROWS ONLY";
			}
		} catch (SQLException e) {
			// Cannot determine product — default to LIMIT 1 which works on
			// PostgreSQL, MySQL, MariaDB, H2, and SQLite.
		}
		return base + "LIMIT 1";
	}

	@Override
	public JournalEntry append(String sagaId, String toolName, Phase phase, String input, String payload) {
		SQLException lastConflict = null;
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			try {
				return tryAppend(sagaId, toolName, phase, input, payload);
			} catch (SQLException ex) {
				if (!isUniqueViolation(ex)) {
					throw new IllegalStateException("Failed to append journal entry for saga " + sagaId, ex);
				}
				// Another append claimed this seq. Re-read the tail and retry.
				lastConflict = ex;
			}
		}
		throw new IllegalStateException("Failed to append journal entry for saga " + sagaId + " after "
				+ MAX_ATTEMPTS + " attempts under contention", lastConflict);
	}

	private static boolean isUniqueViolation(SQLException ex) {
		String state = ex.getSQLState();
		return UNIQUE_VIOLATION_23505.equals(state) || UNIQUE_VIOLATION_23000.equals(state);
	}

	private JournalEntry tryAppend(String sagaId, String toolName, Phase phase, String input, String payload)
			throws SQLException {
		try (Connection conn = dataSource.getConnection()) {
			conn.setAutoCommit(false);
			try {
				long nextSeq = 1;
				String previousHash = HashChain.zeroHash();

				try (PreparedStatement ps = conn.prepareStatement(selectLastSql)) {
					ps.setString(1, sagaId);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							nextSeq = rs.getLong("seq") + 1;
							previousHash = rs.getString("hash");
						}
					}
				}

				// Truncate to microseconds — the value hashed must be byte-identical
				// to the value the database stores and returns.
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
	 * Verifies the hash chain integrity for a saga.
	 *
	 * @return {@code true} if every entry's hash is consistent with the chain
	 *         and no tampering is detected
	 */
	public boolean verifyChain(String sagaId) {
		return HashChain.verify(entries(sagaId));
	}
}
