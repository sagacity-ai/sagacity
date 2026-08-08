package dev.sagacity.core.approval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * Database-backed {@link ApprovalStore}.
 *
 * <p>{@link InMemoryApprovalStore} loses every pending request when the process
 * restarts. That is worse than it sounds: the journal still holds the
 * {@code AWAITING_APPROVAL} entry, so the saga looks like it is waiting for a
 * human forever, but the request carrying the approved payload hash is gone and
 * the tool can never be resumed. A deploy during business hours would strand
 * every in-flight approval.
 *
 * <p>Requests are keyed by {@code (saga_id, journal_seq)}, the same pair the
 * approval and resume APIs take. Saving the same key twice overwrites, so a
 * retried gate does not accumulate duplicates.
 */
public final class PostgresApprovalStore implements ApprovalStore {

	private static final String COLUMNS = "saga_id, journal_seq, tool_name, input, input_hash, created_at";

	private final DataSource dataSource;

	public PostgresApprovalStore(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void save(ApprovalRequest request) {
		// Upsert: re-requesting approval for the same saga/seq replaces rather
		// than duplicating, so find() cannot become ambiguous.
		String sql = "MERGE INTO sagacity_approval_request (" + COLUMNS + ") KEY (saga_id, journal_seq) "
				+ "VALUES (?, ?, ?, ?, ?, ?)";
		String postgres = "INSERT INTO sagacity_approval_request (" + COLUMNS + ") "
				+ "VALUES (?, ?, ?, ?, ?, ?) "
				+ "ON CONFLICT (saga_id, journal_seq) DO UPDATE SET "
				+ "tool_name = EXCLUDED.tool_name, input = EXCLUDED.input, "
				+ "input_hash = EXCLUDED.input_hash, created_at = EXCLUDED.created_at";

		try (Connection conn = this.dataSource.getConnection()) {
			try (PreparedStatement ps = conn.prepareStatement(supportsOnConflict(conn) ? postgres : sql)) {
				ps.setString(1, request.sagaId());
				ps.setLong(2, request.journalSeq());
				ps.setString(3, request.toolName());
				ps.setString(4, request.input());
				ps.setString(5, request.inputHash());
				ps.setTimestamp(6, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
				ps.executeUpdate();
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("Failed to save approval request for saga " + request.sagaId(), e);
		}
	}

	@Override
	public List<ApprovalRequest> pendingRequests() {
		return query("SELECT " + COLUMNS + " FROM sagacity_approval_request ORDER BY created_at, saga_id, journal_seq",
				null);
	}

	@Override
	public List<ApprovalRequest> pendingRequests(String sagaId) {
		return query("SELECT " + COLUMNS + " FROM sagacity_approval_request WHERE saga_id = ? ORDER BY journal_seq",
				sagaId);
	}

	@Override
	public Optional<ApprovalRequest> find(String sagaId, long journalSeq) {
		String sql = "SELECT " + COLUMNS + " FROM sagacity_approval_request "
				+ "WHERE saga_id = ? AND journal_seq = ?";
		try (Connection conn = this.dataSource.getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, sagaId);
			ps.setLong(2, journalSeq);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(map(rs)) : Optional.empty();
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("Failed to look up approval request for saga " + sagaId, e);
		}
	}

	@Override
	public void remove(String sagaId, long journalSeq) {
		String sql = "DELETE FROM sagacity_approval_request WHERE saga_id = ? AND journal_seq = ?";
		try (Connection conn = this.dataSource.getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, sagaId);
			ps.setLong(2, journalSeq);
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new IllegalStateException("Failed to remove approval request for saga " + sagaId, e);
		}
	}

	private List<ApprovalRequest> query(String sql, String sagaIdOrNull) {
		try (Connection conn = this.dataSource.getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
			if (sagaIdOrNull != null) {
				ps.setString(1, sagaIdOrNull);
			}
			try (ResultSet rs = ps.executeQuery()) {
				List<ApprovalRequest> results = new ArrayList<>();
				while (rs.next()) {
					results.add(map(rs));
				}
				return List.copyOf(results);
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("Failed to read approval requests", e);
		}
	}

	private static ApprovalRequest map(ResultSet rs) throws SQLException {
		return new ApprovalRequest(rs.getString("saga_id"), rs.getLong("journal_seq"),
				rs.getString("tool_name"), rs.getString("input"), rs.getString("input_hash"));
	}

	/** H2 (used in tests) predates ON CONFLICT in PostgreSQL mode; it takes MERGE. */
	private static boolean supportsOnConflict(Connection conn) throws SQLException {
		return !"H2".equalsIgnoreCase(conn.getMetaData().getDatabaseProductName());
	}

}
