package dev.sagacity.workflows.store;

import dev.sagacity.workflows.WorkflowRun;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link WorkflowRunStore}. Default when no {@code DataSource} is present.
 *
 * <p>Runs are held in a {@link ConcurrentHashMap} and are lost on JVM restart.
 * Suitable for development, testing, and applications that do not require gate
 * approvals to survive restarts.
 */
public final class InMemoryWorkflowRunStore implements WorkflowRunStore {

    private final Map<String, WorkflowRun> store = new ConcurrentHashMap<>();

    @Override
    public void save(WorkflowRun run) {
        store.put(run.runId(), run);
    }

    @Override
    public Optional<WorkflowRun> load(String runId) {
        return Optional.ofNullable(store.get(runId));
    }

    @Override
    public List<WorkflowRun> loadAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void delete(String runId) {
        store.remove(runId);
    }
}
