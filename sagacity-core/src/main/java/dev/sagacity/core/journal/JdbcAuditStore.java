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
 * JDBC-backed {@link AuditStore} with SHA-256 hash chain for tamper evidence.
 *
 * <p>Works with any JDBC-compatible database: PostgreSQL, MySQL, MariaDB,
 * Oracle, H2, SQLite.
 *
 * <h2>Schema</h2>
 * <pre>{@code
 * CREATE TABLE sagacity_journal (
 *     saga_id     TEXT      NOT NULL,
 *     seq         BIGINT    NOT NULL,
 *     tool_name   TEXT      NOT NULL,
 *     phase       TEXT      NOT NULL,       -- Phase.discriminator()
 *     phase_data  TEXT      NOT NULL,       -- Phase.toJson()
 *     input       TEXT      NOT NULL DEFAULT '',
 *     recorded_at TIMESTAMP NOT NULL,
 *     hash        CHAR(64)  NOT NULL,
 *     PRIMARY KEY (saga_id, seq)
 * );
 * }</pre>
 *
 * <p>The {@code phase} column holds the discriminator (e.g. {@code "Executed"})
 * and {@code phase_data} holds a JSON object with the phase-specific fields
 * (e.g. {@code {"result":"ok"}}). Together they replace the old single
 * {@code payload} column whose meaning varied by phase.
 *
 * <h2>Concurrency</h2>
 * <p>Uses optimistic concurrency — no vendor-specific locking. Each append reads
 * the current tail seq, increments it, and INSERTs. On a primary-key conflict
 * (two concurrent appends computed the same seq) the loser re-reads and retries.
 * This handles the empty-saga case that {@code SELECT FOR UPDATE} cannot.
 *
 * <h2>Timestamp precision</h2>
 * <p>Timestamps are truncated to microseconds before hashing and storage so the
 * value hashed in Java is byte-identical to the value read back from the database.
 */
public final class JdbcAuditStore implements AuditStore {

    /** SQLSTATE 23505 — unique/primary key violation (PostgreSQL, H2). */
    private static final String UNIQUE_VIOLATION_23505 = "23505";

    /** SQLSTATE 23000 — integrity constraint violation (MySQL, MariaDB, Oracle). */
    private static final String UNIQUE_VIOLATION_23000 = "23000";

    private static final int MAX_ATTEMPTS = 50;

    private static final String INSERT_SQL = """
            INSERT INTO sagacity_journal
                (saga_id, seq, tool_name, phase, phase_data, input, recorded_at, hash)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT saga_id, seq, tool_name, phase, phase_data, input, recorded_at, hash
              FROM sagacity_journal
             WHERE saga_id = ?
             ORDER BY seq ASC
            """;

    private static final String SELECT_BY_PHASE_SQL = """
            SELECT saga_id, seq, tool_name, phase, phase_data, input, recorded_at, hash
              FROM sagacity_journal
             WHERE saga_id = ?
               AND phase   = ?
             ORDER BY seq ASC
            """;

    private final DataSource dataSource;

    /** Database-specific SQL to read the last entry's seq and hash for a saga. */
    private final String selectLastSql;

    public JdbcAuditStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.selectLastSql = buildSelectLastSql(dataSource);
    }

    private static String buildSelectLastSql(DataSource ds) {
        String base = """
                SELECT seq, hash FROM sagacity_journal
                 WHERE saga_id = ?
                 ORDER BY seq DESC\s
                """;
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName();
            if (product != null && product.toLowerCase().contains("oracle")) {
                return base + "FETCH FIRST 1 ROWS ONLY";
            }
        } catch (SQLException ignored) {
            // Cannot determine product — default to LIMIT 1 (Postgres, MySQL, H2, SQLite).
        }
        return base + "LIMIT 1";
    }

    @Override
    public AuditEntry append(String sagaId, String toolName, Phase phase, String input) {
        SQLException lastConflict = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return tryAppend(sagaId, toolName, phase, input);
            } catch (SQLException ex) {
                if (!isUniqueViolation(ex)) {
                    throw new IllegalStateException(
                            "Failed to append audit entry for saga " + sagaId, ex);
                }
                lastConflict = ex;
            }
        }
        throw new IllegalStateException(
                "Failed to append audit entry for saga " + sagaId
                + " after " + MAX_ATTEMPTS + " attempts under contention", lastConflict);
    }

    private AuditEntry tryAppend(String sagaId, String toolName, Phase phase, String input)
            throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long nextSeq = 1L;
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

                Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);
                String hash = HashChain.computeHash(previousHash, sagaId, nextSeq,
                        toolName, phase, input, timestamp);

                try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                    ps.setString(1, sagaId);
                    ps.setLong(2, nextSeq);
                    ps.setString(3, toolName);
                    ps.setString(4, phase.discriminator());
                    ps.setString(5, phase.toJson());
                    ps.setString(6, input != null ? input : "");
                    ps.setTimestamp(7, Timestamp.from(timestamp));
                    ps.setString(8, hash);
                    ps.executeUpdate();
                }

                conn.commit();
                return new AuditEntry(sagaId, nextSeq, toolName, phase, input, timestamp, hash);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ALL_SQL)) {
            ps.setString(1, sagaId);
            return readEntries(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read audit entries for saga " + sagaId, e);
        }
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId, Class<? extends Phase> phaseType) {
        // Push the filter to the database using the discriminator string.
        // Construct an instance to get the discriminator — use the canonical name.
        String discriminator = phaseType.getSimpleName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_PHASE_SQL)) {
            ps.setString(1, sagaId);
            ps.setString(2, discriminator);
            return readEntries(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to read audit entries for saga " + sagaId
                    + " phase " + discriminator, e);
        }
    }

    /**
     * Verifies the hash chain integrity for a saga.
     *
     * @return {@code true} if every entry's hash is consistent with the chain
     */
    public boolean verifyChain(String sagaId) {
        return HashChain.verify(findBySagaId(sagaId));
    }

    private static List<AuditEntry> readEntries(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<AuditEntry> results = new ArrayList<>();
            while (rs.next()) {
                Phase phase = Phase.fromStorage(
                        rs.getString("phase"),
                        rs.getString("phase_data"));
                results.add(new AuditEntry(
                        rs.getString("saga_id"),
                        rs.getLong("seq"),
                        rs.getString("tool_name"),
                        phase,
                        rs.getString("input"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getString("hash")));
            }
            return results;
        }
    }

    private static boolean isUniqueViolation(SQLException ex) {
        String state = ex.getSQLState();
        return UNIQUE_VIOLATION_23505.equals(state) || UNIQUE_VIOLATION_23000.equals(state);
    }
}
