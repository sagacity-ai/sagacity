package dev.sagacity.workflows.store;

import dev.sagacity.workflows.WorkflowRun;
import dev.sagacity.workflows.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JdbcWorkflowRunStore} against an in-memory H2 database.
 */
class JdbcWorkflowRunStoreTest {

    private DataSource dataSource;
    private JdbcWorkflowRunStore store;

    @BeforeEach
    void setUp() throws Exception {
        // Unique DB name per test instance prevents cross-test row bleed
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("testdb-" + System.nanoTime())
                .build();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS sagacity_workflow_runs (
                        run_id                  VARCHAR(36)  NOT NULL,
                        workflow_name           VARCHAR(255) NOT NULL,
                        status                  VARCHAR(50)  NOT NULL,
                        current_stage_order     INT          NOT NULL DEFAULT 0,
                        pending_gate_stage      VARCHAR(255),
                        completed_stages        TEXT,
                        last_stage_output       TEXT,
                        last_stage_output_type  VARCHAR(512),
                        failure_reason          TEXT,
                        started_at              TIMESTAMP    NOT NULL,
                        completed_at            TIMESTAMP,
                        PRIMARY KEY (run_id)
                    )
                    """);
        }
        store = new JdbcWorkflowRunStore(dataSource);
    }

    @Test
    void saveAndLoadRoundTrip_newRun() {
        WorkflowRun run = new WorkflowRun("run-1", "my-workflow");
        store.save(run);

        Optional<WorkflowRun> loaded = store.load("run-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().runId()).isEqualTo("run-1");
        assertThat(loaded.get().workflowName()).isEqualTo("my-workflow");
        assertThat(loaded.get().status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(loaded.get().completedStages()).isEmpty();
        assertThat(loaded.get().startedAt()).isNotNull();
    }

    @Test
    void save_updatesExistingRow() {
        // Save completed run via reconstitute
        WorkflowRun run = WorkflowRun.reconstitute(
                "run-2", "update-workflow", WorkflowStatus.COMPLETED,
                2, null, List.of("stage1", "stage2"), "result",
                null, Instant.now(), Instant.now());
        store.save(run);

        WorkflowRun loaded = store.load("run-2").orElseThrow();
        assertThat(loaded.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(loaded.completedStages()).containsExactly("stage1", "stage2");
        assertThat(loaded.completedAt()).isPresent();
    }

    @Test
    void save_persistsPendingGate() {
        WorkflowRun run = WorkflowRun.reconstitute(
                "run-3", "gate-workflow", WorkflowStatus.PAUSED_AT_GATE,
                1, "approvalStage", List.of("stage1"), null,
                null, Instant.now(), null);
        store.save(run);

        WorkflowRun loaded = store.load("run-3").orElseThrow();
        assertThat(loaded.status()).isEqualTo(WorkflowStatus.PAUSED_AT_GATE);
        assertThat(loaded.pendingGateStageName()).contains("approvalStage");
    }

    @Test
    void save_persistsFailureReason() {
        WorkflowRun run = WorkflowRun.reconstitute(
                "run-4", "fail-workflow", WorkflowStatus.FAILED,
                2, null, List.of("stage1"), null,
                "something went wrong", Instant.now(), Instant.now());
        store.save(run);

        WorkflowRun loaded = store.load("run-4").orElseThrow();
        assertThat(loaded.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(loaded.failureReason()).contains("something went wrong");
        assertThat(loaded.completedAt()).isPresent();
    }

    @Test
    void save_persistsStringStageOutput() {
        WorkflowRun run = WorkflowRun.reconstitute(
                "run-5", "output-workflow", WorkflowStatus.RUNNING,
                1, null, List.of("stage1"), "order-12345",
                null, Instant.now(), null);
        store.save(run);

        WorkflowRun loaded = store.load("run-5").orElseThrow();
        assertThat(loaded.lastStageOutput()).isEqualTo("order-12345");
    }

    @Test
    void loadAll_returnsAllSavedRuns() {
        store.save(new WorkflowRun("r1", "wf-a"));
        store.save(new WorkflowRun("r2", "wf-b"));
        store.save(new WorkflowRun("r3", "wf-c"));

        List<WorkflowRun> all = store.loadAll();
        assertThat(all).hasSize(3);
        assertThat(all).extracting(WorkflowRun::runId)
                .containsExactlyInAnyOrder("r1", "r2", "r3");
    }

    @Test
    void delete_removesRun() {
        store.save(new WorkflowRun("run-del", "delete-workflow"));
        assertThat(store.load("run-del")).isPresent();

        store.delete("run-del");
        assertThat(store.load("run-del")).isEmpty();
    }

    @Test
    void load_returnsEmpty_forUnknownId() {
        assertThat(store.load("does-not-exist")).isEmpty();
    }

    @Test
    void save_persistsMultipleCompletedStages() {
        WorkflowRun run = WorkflowRun.reconstitute(
                "run-stages", "multi-stage", WorkflowStatus.RUNNING,
                3, null, List.of("stage1", "stage2", "stage3"), null,
                null, Instant.now(), null);
        store.save(run);

        WorkflowRun loaded = store.load("run-stages").orElseThrow();
        assertThat(loaded.completedStages())
                .containsExactly("stage1", "stage2", "stage3");
    }

    @Test
    void save_isIdempotent_onSameRun() {
        WorkflowRun run = new WorkflowRun("run-idem", "wf");
        store.save(run);
        store.save(run); // should not throw or duplicate
        assertThat(store.loadAll()).hasSize(1);
    }
}
