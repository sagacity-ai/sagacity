package dev.sagacity.workflows.store;

import dev.sagacity.workflows.WorkflowRun;

import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for {@link WorkflowRun} instances.
 *
 * <p>Implementations must be thread-safe. Two implementations are provided:
 * <ul>
 *   <li>{@link InMemoryWorkflowRunStore} — default, runs lost on JVM restart</li>
 *   <li>{@link JdbcWorkflowRunStore} — durable, survives restarts</li>
 * </ul>
 *
 * <p>Auto-configuration selects {@link JdbcWorkflowRunStore} when a
 * {@code DataSource} bean is present, otherwise falls back to in-memory.
 */
public interface WorkflowRunStore {

    /**
     * Persist or update a run. Called after every state transition.
     *
     * @param run the run to save (insert or update)
     */
    void save(WorkflowRun run);

    /**
     * Load a single run by ID.
     *
     * @param runId the run identifier
     * @return the run, or empty if not found
     */
    Optional<WorkflowRun> load(String runId);

    /**
     * Load all runs known to this store, in no guaranteed order.
     *
     * @return unmodifiable snapshot of all runs
     */
    List<WorkflowRun> loadAll();

    /**
     * Remove a run from the store. Used for cleanup — not called during normal
     * execution; callers may choose to retain completed/failed runs for audit.
     *
     * @param runId the run to remove
     */
    void delete(String runId);
}
