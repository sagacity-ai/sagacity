package dev.sagacity.workflows.store;

import dev.sagacity.workflows.WorkflowRun;
import dev.sagacity.workflows.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryWorkflowRunStoreTest {

    private final InMemoryWorkflowRunStore store = new InMemoryWorkflowRunStore();

    @Test
    void saveAndLoad() {
        WorkflowRun run = new WorkflowRun("run-1", "wf");
        store.save(run);
        assertThat(store.load("run-1")).contains(run);
    }

    @Test
    void loadAll_returnsAllSaved() {
        store.save(new WorkflowRun("r1", "wf"));
        store.save(new WorkflowRun("r2", "wf"));
        assertThat(store.loadAll()).hasSize(2);
    }

    @Test
    void delete_removesRun() {
        store.save(new WorkflowRun("run-del", "wf"));
        store.delete("run-del");
        assertThat(store.load("run-del")).isEmpty();
    }

    @Test
    void save_overwritesExisting() {
        WorkflowRun original = new WorkflowRun("run-upd", "wf");
        store.save(original);

        // Replace with a reconstituted completed run
        WorkflowRun updated = WorkflowRun.reconstitute(
                "run-upd", "wf", WorkflowStatus.COMPLETED,
                1, null, List.of("stage1"), null,
                null, Instant.now(), Instant.now());
        store.save(updated);

        assertThat(store.load("run-upd").orElseThrow().status())
                .isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void load_returnsEmpty_forUnknownId() {
        assertThat(store.load("no-such-run")).isEmpty();
    }
}
