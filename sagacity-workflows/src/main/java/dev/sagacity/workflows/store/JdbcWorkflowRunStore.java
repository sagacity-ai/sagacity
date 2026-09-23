package dev.sagacity.workflows.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sagacity.workflows.WorkflowRun;
import dev.sagacity.workflows.WorkflowStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * JDBC-backed {@link WorkflowRunStore}. Persists workflow run state to the
 * {@code sagacity_workflow_runs} table so runs survive JVM restarts.
 *
 * <p>Works with any JDBC database supported by {@code JdbcSideEffectJournal}:
 * PostgreSQL, MySQL, MariaDB, Oracle, H2, SQLite.
 *
 * <h2>Stage output serialization</h2>
 * <p>The {@code lastStageOutput} field is serialized to JSON via Jackson and
 * stored alongside its fully-qualified class name so it can be deserialized on
 * resume. Stage outputs must be Jackson-serializable (standard POJOs, records,
 * strings, numbers). If serialization fails the output is stored as
 * {@code null} — the stage will be replayed with no chained input on resume,
 * which is safe for idempotent stages.
 *
 * <h2>Upsert strategy</h2>
 * <p>{@link #save} uses a DELETE + INSERT pattern rather than vendor-specific
 * UPSERT syntax (ON CONFLICT, ON DUPLICATE KEY UPDATE) so the same SQL works
 * across all supported databases.
 */
public final class JdbcWorkflowRunStore implements WorkflowRunStore {

    private static final Logger log = Logger.getLogger(JdbcWorkflowRunStore.class.getName());

    private static final String INSERT_SQL =
            "INSERT INTO sagacity_workflow_runs " +
            "(run_id, workflow_name, status, current_stage_order, pending_gate_stage, " +
            " completed_stages, last_stage_output, last_stage_output_type, " +
            " failure_reason, started_at, completed_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String DELETE_SQL =
            "DELETE FROM sagacity_workflow_runs WHERE run_id = ?";

    private static final String SELECT_ONE_SQL =
            "SELECT run_id, workflow_name, status, current_stage_order, pending_gate_stage, " +
            "completed_stages, last_stage_output, last_stage_output_type, " +
            "failure_reason, started_at, completed_at " +
            "FROM sagacity_workflow_runs WHERE run_id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT run_id, workflow_name, status, current_stage_order, pending_gate_stage, " +
            "completed_stages, last_stage_output, last_stage_output_type, " +
            "failure_reason, started_at, completed_at " +
            "FROM sagacity_workflow_runs ORDER BY started_at DESC";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowRunStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    /**
     * Convenience constructor — creates a default {@link ObjectMapper}.
     * Prefer the two-arg constructor in Spring context so the app's configured
     * mapper (with any custom serializers) is reused.
     */
    public JdbcWorkflowRunStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper().findAndRegisterModules());
    }

    // -------------------------------------------------------------------------
    // WorkflowRunStore implementation
    // -------------------------------------------------------------------------

    @Override
    public void save(WorkflowRun run) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // DELETE + INSERT — works on all databases without vendor-specific UPSERT
                try (PreparedStatement del = conn.prepareStatement(DELETE_SQL)) {
                    del.setString(1, run.runId());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(INSERT_SQL)) {
                    ins.setString(1, run.runId());
                    ins.setString(2, run.workflowName());
                    ins.setString(3, run.status().name());
                    ins.setInt(4, run.currentStageOrder());
                    ins.setString(5, run.pendingGateStageName().orElse(null));
                    ins.setString(6, serializeStages(run.completedStages()));
                    ins.setString(7, serializeOutput(run.lastStageOutput()));
                    ins.setString(8, outputTypeName(run.lastStageOutput()));
                    ins.setString(9, run.failureReason().orElse(null));
                    ins.setTimestamp(10, Timestamp.from(run.startedAt()));
                    ins.setTimestamp(11, run.completedAt().map(Timestamp::from).orElse(null));
                    ins.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save workflow run " + run.runId(), e);
        }
    }

    @Override
    public Optional<WorkflowRun> load(String runId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ONE_SQL)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load workflow run " + runId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<WorkflowRun> loadAll() {
        List<WorkflowRun> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load workflow runs", e);
        }
        return List.copyOf(results);
    }

    @Override
    public void delete(String runId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_SQL)) {
            ps.setString(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete workflow run " + runId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Row mapping
    // -------------------------------------------------------------------------

    private WorkflowRun mapRow(ResultSet rs) throws SQLException {
        String runId        = rs.getString("run_id");
        String workflowName = rs.getString("workflow_name");
        WorkflowStatus status = WorkflowStatus.valueOf(rs.getString("status"));
        int stageOrder      = rs.getInt("current_stage_order");
        String pendingGate  = rs.getString("pending_gate_stage");
        List<String> stages = deserializeStages(rs.getString("completed_stages"));
        Object output       = deserializeOutput(rs.getString("last_stage_output"),
                                                rs.getString("last_stage_output_type"));
        String failureReason = rs.getString("failure_reason");
        Instant startedAt   = rs.getTimestamp("started_at").toInstant();
        Timestamp completedTs = rs.getTimestamp("completed_at");
        Instant completedAt = completedTs != null ? completedTs.toInstant() : null;

        return WorkflowRun.reconstitute(
                runId, workflowName, status, stageOrder,
                pendingGate, stages, output, failureReason,
                startedAt, completedAt);
    }

    // -------------------------------------------------------------------------
    // Serialization helpers
    // -------------------------------------------------------------------------

    private String serializeStages(List<String> stages) {
        try {
            return objectMapper.writeValueAsString(stages);
        } catch (JsonProcessingException e) {
            log.warning("[sagacity-workflows] failed to serialize completed stages: " + e.getMessage());
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> deserializeStages(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            log.warning("[sagacity-workflows] failed to deserialize completed stages: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private String serializeOutput(Object output) {
        if (output == null) return null;
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            log.warning("[sagacity-workflows] failed to serialize stage output (" +
                    output.getClass().getName() + "): " + e.getMessage());
            return null;
        }
    }

    private String outputTypeName(Object output) {
        return output != null ? output.getClass().getName() : null;
    }

    private Object deserializeOutput(String json, String typeName) {
        if (json == null || typeName == null) return null;
        try {
            Class<?> type = Class.forName(typeName);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warning("[sagacity-workflows] failed to deserialize stage output (type=" +
                    typeName + "): " + e.getMessage());
            return null;
        }
    }
}
