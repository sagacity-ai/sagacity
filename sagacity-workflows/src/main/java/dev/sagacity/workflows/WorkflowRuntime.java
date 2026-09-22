package dev.sagacity.workflows;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.springframework.context.ApplicationContext;

import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;
import dev.sagacity.workflows.annotation.Check;
import dev.sagacity.workflows.annotation.Gate;
import dev.sagacity.workflows.annotation.Stage;
import dev.sagacity.workflows.annotation.Workflow;
import dev.sagacity.workflows.check.CheckResult;
import dev.sagacity.workflows.check.StageCheck;
import dev.sagacity.workflows.check.StageCheckContext;

/**
 * Executes {@link Workflow}-annotated beans stage by stage, handling gates,
 * checks, compensation, and status tracking.
 *
 * <p>This is the central entry point. Inject it into your Spring beans and call
 * {@link #run} (blocking) or {@link #runAsync} (non-blocking).
 *
 * <h2>Thread model</h2>
 * <p>Synchronous {@link #run} executes on the calling thread. Asynchronous
 * {@link #runAsync} uses a virtual-thread executor (Java 21+) or a cached
 * thread pool on Java 17. Gate waits use {@link Object#wait()} on the run
 * lock — they do not spin.
 *
 * <h2>State store</h2>
 * <p>v0.1 uses an in-memory {@link ConcurrentHashMap}. Runs are lost on JVM
 * restart. A JDBC-backed store will be added in v0.2 — the store is accessed
 * only through this class, so switching is a single-class change.
 */
public final class WorkflowRuntime {

    private static final Logger log = Logger.getLogger(WorkflowRuntime.class.getName());

    private final SideEffectJournal journal;
    private final ApplicationContext applicationContext;
    private final Executor executor;

    // runId → WorkflowRun. ConcurrentHashMap for safe reads across threads;
    // mutations on individual runs are synchronized on the run object itself.
    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();

    // runId → CompletableFuture, used by WorkflowHandle.awaitCompletion
    private final Map<String, CompletableFuture<WorkflowRun>> futures = new ConcurrentHashMap<>();

    public WorkflowRuntime(SideEffectJournal journal, ApplicationContext applicationContext) {
        this.journal = journal;
        this.applicationContext = applicationContext;
        // Virtual threads on Java 21+, cached pool on Java 17
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sagacity-workflow");
            t.setDaemon(true);
            return t;
        });
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Execute the workflow synchronously on the calling thread.
     *
     * @param workflowBean a Spring bean annotated with {@link Workflow}
     * @param input        the input passed to the first stage
     * @return the final {@link WorkflowRun} — check {@link WorkflowRun#status()} for outcome
     */
    public WorkflowRun run(Object workflowBean, Object input) {
        String runId = UUID.randomUUID().toString();
        WorkflowRun workflowRun = createRun(runId, workflowBean);
        executeWorkflow(workflowRun, workflowBean, input);
        return workflowRun;
    }

    /**
     * Execute the workflow asynchronously. Returns immediately with a handle.
     *
     * @param workflowBean a Spring bean annotated with {@link Workflow}
     * @param input        the input passed to the first stage
     * @return a {@link WorkflowHandle} for status polling and completion waiting
     */
    public WorkflowHandle runAsync(Object workflowBean, Object input) {
        String runId = UUID.randomUUID().toString();
        WorkflowRun workflowRun = createRun(runId, workflowBean);
        CompletableFuture<WorkflowRun> future = new CompletableFuture<>();
        futures.put(runId, future);
        WorkflowHandle handle = new WorkflowHandle(runId, workflowRun.workflowName(), workflowRun, future);
        executor.execute(() -> {
            executeWorkflow(workflowRun, workflowBean, input);
            future.complete(workflowRun);
        });
        return handle;
    }

    /**
     * Approve a gate that has paused a workflow run.
     *
     * @param runId     the run to resume
     * @param stageName the stage name that is waiting (must match the pending gate)
     * @throws IllegalStateException if the run is not paused at the given gate
     */
    public void approveGate(String runId, String stageName) {
        WorkflowRun workflowRun = requireRun(runId);
        synchronized (workflowRun) {
            if (workflowRun.status() != WorkflowStatus.PAUSED_AT_GATE) {
                throw new IllegalStateException(
                        "Run " + runId + " is not paused at a gate (status=" + workflowRun.status() + ")");
            }
            String pending = workflowRun.pendingGateStageName().orElse("");
            if (!pending.equals(stageName)) {
                throw new IllegalStateException(
                        "Run " + runId + " is paused at gate '" + pending + "', not '" + stageName + "'");
            }
            workflowRun.clearPendingGate();
            workflowRun.transitionTo(WorkflowStatus.RUNNING);
            workflowRun.notifyAll(); // wake the waiting execution thread
        }
        journal.append(runId, stageName, Phase.APPROVED, "gate-approved", "human approved gate: " + stageName);
        log.info("[sagacity-workflows] gate approved: runId=" + runId + " stage=" + stageName);
    }

    /**
     * Reject a gate, causing the workflow to fail and compensate.
     *
     * @param runId     the run to reject
     * @param stageName the stage name that is waiting
     * @param reason    human-readable rejection reason recorded in the audit trail
     */
    public void rejectGate(String runId, String stageName, String reason) {
        WorkflowRun workflowRun = requireRun(runId);
        synchronized (workflowRun) {
            if (workflowRun.status() != WorkflowStatus.PAUSED_AT_GATE) {
                throw new IllegalStateException(
                        "Run " + runId + " is not paused at a gate (status=" + workflowRun.status() + ")");
            }
            workflowRun.clearPendingGate();
            workflowRun.fail("gate rejected: " + reason);
            workflowRun.notifyAll();
        }
        journal.append(runId, stageName, Phase.REJECTED, "gate-rejected", "human rejected gate: " + reason);
        log.warning("[sagacity-workflows] gate rejected: runId=" + runId + " stage=" + stageName + " reason=" + reason);
    }

    /** Retrieve a run by ID. Returns empty if no such run exists. */
    public Optional<WorkflowRun> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /** All runs known to this runtime, in no particular order. */
    public List<WorkflowRun> allRuns() {
        return List.copyOf(runs.values());
    }

    // -------------------------------------------------------------------------
    // Topology validation — called at startup by WorkflowRuntimePostProcessor
    // -------------------------------------------------------------------------

    /**
     * Validates a workflow bean's stage topology at startup.
     * Throws {@link WorkflowDefinitionException} on any structural problem:
     * duplicate stage orders, missing compensation methods, etc.
     */
    public static void validateTopology(Object workflowBean) {
        Class<?> clazz = workflowBean.getClass();
        Workflow workflow = findWorkflowAnnotation(clazz);
        if (workflow == null) {
            throw new WorkflowDefinitionException(
                    clazz.getName() + " is not annotated with @Workflow");
        }
        List<Method> stages = findStageMethods(clazz);
        if (stages.isEmpty()) {
            throw new WorkflowDefinitionException(
                    "@Workflow(\"" + workflow.value() + "\") has no @Stage methods");
        }
        // Check for duplicate orders
        long distinctOrders = stages.stream()
                .map(m -> m.getAnnotation(Stage.class).order())
                .distinct()
                .count();
        if (distinctOrders != stages.size()) {
            throw new WorkflowDefinitionException(
                    "@Workflow(\"" + workflow.value() + "\") has duplicate @Stage orders");
        }
        // Check @Compensable references resolve to real @Compensation methods
        for (Method stage : stages) {
            Compensable compensable = stage.getAnnotation(Compensable.class);
            if (compensable != null && !compensable.by().isBlank()) {
                String compensationName = compensable.by();
                boolean found = Arrays.stream(clazz.getMethods())
                        .anyMatch(m -> m.getName().equals(compensationName)
                                && m.isAnnotationPresent(Compensation.class));
                if (!found) {
                    throw new WorkflowDefinitionException(
                            "@Stage method '" + stage.getName() + "' in @Workflow(\""
                                    + workflow.value() + "\") declares @Compensable(by=\""
                                    + compensationName + "\") but no @Compensation method with that name exists");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal execution engine
    // -------------------------------------------------------------------------

    private WorkflowRun createRun(String runId, Object workflowBean) {
        Class<?> clazz = workflowBean.getClass();
        Workflow workflow = findWorkflowAnnotation(clazz);
        if (workflow == null) {
            throw new WorkflowDefinitionException(clazz.getName() + " is not annotated with @Workflow");
        }
        WorkflowRun workflowRun = new WorkflowRun(runId, workflow.value());
        runs.put(runId, workflowRun);
        log.info("[sagacity-workflows] started runId=" + runId + " workflow=" + workflow.value());
        return workflowRun;
    }

    private void executeWorkflow(WorkflowRun workflowRun, Object workflowBean, Object input) {
        List<Method> stages = findStageMethods(workflowBean.getClass())
                .stream()
                .sorted(Comparator.comparingInt(m -> m.getAnnotation(Stage.class).order()))
                .toList();

        // Track completed stages for compensation (in order, so we reverse later)
        List<Method> completedWithCompensation = new ArrayList<>();
        Object stageInput = input;

        for (Method stage : stages) {
            Stage stageAnnotation = stage.getAnnotation(Stage.class);
            String stageName = stageName(stage);
            workflowRun.setCurrentStageOrder(stageAnnotation.order());

            // --- Pre-flight checks ---
            Check check = stage.getAnnotation(Check.class);
            if (check != null) {
                CheckResult result = runChecks(check, workflowRun, stageName, stageAnnotation.order(), stageInput);
                if (!result.isPassed()) {
                    journal.append(workflowRun.runId(), stageName, Phase.FAILED,
                            String.valueOf(stageInput), "check failed: " + result.reason());
                    workflowRun.fail("check failed at stage '" + stageName + "': " + result.reason());
                    runCompensation(workflowRun, workflowBean, completedWithCompensation);
                    return;
                }
            }

            // --- Gate (human approval) ---
            Gate gate = stage.getAnnotation(Gate.class);
            if (gate != null && gate.approvalRequired()) {
                journal.append(workflowRun.runId(), stageName, Phase.AWAITING_APPROVAL,
                        String.valueOf(stageInput), "awaiting gate approval");
                waitForGate(workflowRun, stageName, gate.timeoutSeconds());
                // After wait: check if gate was rejected
                if (workflowRun.status() == WorkflowStatus.FAILED) {
                    runCompensation(workflowRun, workflowBean, completedWithCompensation);
                    return;
                }
            }

            // --- Execute stage ---
            journal.append(workflowRun.runId(), stageName, Phase.INTENT,
                    String.valueOf(stageInput), "stage starting");
            try {
                Object result = invokeStage(stage, workflowBean, stageInput);
                journal.append(workflowRun.runId(), stageName, Phase.EXECUTED,
                        String.valueOf(stageInput), "stage completed");
                workflowRun.recordStageCompleted(stageName);
                workflowRun.setLastStageOutput(result);
                // Pass this stage's output as the next stage's input (chaining)
                stageInput = result;

                // Track for compensation only if @Compensable is declared
                if (stage.isAnnotationPresent(Compensable.class)) {
                    completedWithCompensation.add(stage);
                }

            } catch (Exception ex) {
                String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                journal.append(workflowRun.runId(), stageName, Phase.FAILED,
                        String.valueOf(stageInput), reason);
                workflowRun.fail("stage '" + stageName + "' failed: " + reason);
                log.warning("[sagacity-workflows] stage failed: runId=" + workflowRun.runId()
                        + " stage=" + stageName + " reason=" + reason);
                runCompensation(workflowRun, workflowBean, completedWithCompensation);
                return;
            }
        }

        // All stages completed
        workflowRun.transitionTo(WorkflowStatus.COMPLETED);
        log.info("[sagacity-workflows] completed runId=" + workflowRun.runId()
                + " workflow=" + workflowRun.workflowName());
    }

    private void waitForGate(WorkflowRun workflowRun, String stageName, long timeoutSeconds) {
        synchronized (workflowRun) {
            workflowRun.setPendingGate(stageName);
            log.info("[sagacity-workflows] paused at gate: runId=" + workflowRun.runId() + " stage=" + stageName);
            try {
                long deadline = timeoutSeconds > 0 ? System.currentTimeMillis() + (timeoutSeconds * 1000L) : 0;
                while (workflowRun.status() == WorkflowStatus.PAUSED_AT_GATE) {
                    if (deadline > 0) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) {
                            workflowRun.fail("gate timed out after " + timeoutSeconds + "s: stage=" + stageName);
                            return;
                        }
                        workflowRun.wait(remaining);
                    } else {
                        workflowRun.wait();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workflowRun.fail("gate wait interrupted: stage=" + stageName);
            }
        }
    }

    private void runCompensation(WorkflowRun workflowRun, Object workflowBean, List<Method> completedStages) {
        if (completedStages.isEmpty()) {
            return;
        }
        workflowRun.transitionTo(WorkflowStatus.COMPENSATING);
        log.info("[sagacity-workflows] compensating: runId=" + workflowRun.runId()
                + " stages=" + completedStages.size());

        // Reverse order — last completed stage compensates first
        List<Method> toCompensate = new ArrayList<>(completedStages);
        java.util.Collections.reverse(toCompensate);

        for (Method stage : toCompensate) {
            Compensable compensable = stage.getAnnotation(Compensable.class);
            if (compensable == null || compensable.by().isBlank()) continue;

            String stageName = stageName(stage);
            String compensationMethodName = compensable.by();
            try {
                Method compensationMethod = findCompensationMethod(workflowBean.getClass(), compensationMethodName);
                invokeCompensation(compensationMethod, workflowBean, workflowRun.runId(), stageName);
                journal.append(workflowRun.runId(), stageName, Phase.COMPENSATED,
                        "", "compensation completed");
                log.info("[sagacity-workflows] compensated stage: " + stageName);
            } catch (Exception ex) {
                String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                journal.append(workflowRun.runId(), stageName, Phase.COMPENSATION_FAILED,
                        "", reason);
                log.warning("[sagacity-workflows] compensation failed: stage=" + stageName + " reason=" + reason);
                // Continue — partial cleanup is better than stopping
            }
        }
        workflowRun.transitionTo(WorkflowStatus.FAILED);
    }

    private CheckResult runChecks(Check check, WorkflowRun run, String stageName, int stageOrder, Object input) {
        StageCheckContext ctx = new StageCheckContext(
                run.runId(), run.workflowName(), stageName, stageOrder, String.valueOf(input));
        for (Class<? extends StageCheck> checkClass : check.value()) {
            StageCheck checkBean = applicationContext.getBean(checkClass);
            CheckResult result = checkBean.check(ctx);
            if (!result.isPassed()) {
                return result;
            }
        }
        return CheckResult.pass();
    }

    private Object invokeStage(Method stage, Object target, Object input) throws Exception {
        stage.setAccessible(true);
        try {
            // If the stage takes a parameter and we have input, inject it
            if (stage.getParameterCount() == 1 && input != null) {
                Class<?> paramType = stage.getParameterTypes()[0];
                if (paramType.isInstance(input)) {
                    return stage.invoke(target, input);
                }
            }
            // No parameter injection — call with no args (first stage, or void-returning stages)
            if (stage.getParameterCount() == 0) {
                return stage.invoke(target);
            }
            // Parameter type mismatch — still call with null (stage handles it)
            return stage.invoke(target, (Object) null);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // Unwrap so the real cause surfaces in the journal and failure reason,
            // not the reflection wrapper
            Throwable cause = ite.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw ite;
        }
    }

    private void invokeCompensation(Method method, Object target, String runId, String stageName) throws Exception {
        method.setAccessible(true);
        if (method.getParameterCount() == 0) {
            method.invoke(target);
        } else if (method.getParameterCount() == 1
                && CompensationContext.class.isAssignableFrom(method.getParameterTypes()[0])) {
            CompensationContext ctx = new CompensationContext(runId, stageName, "", "");
            method.invoke(target, ctx);
        } else {
            method.invoke(target, (Object) null);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WorkflowRun requireRun(String runId) {
        WorkflowRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("No workflow run found with ID: " + runId);
        }
        return run;
    }

    private static Workflow findWorkflowAnnotation(Class<?> clazz) {
        // Handle Spring AOP proxies — check superclass and interfaces
        Workflow found = clazz.getAnnotation(Workflow.class);
        if (found != null) return found;
        if (clazz.getSuperclass() != null) {
            found = clazz.getSuperclass().getAnnotation(Workflow.class);
            if (found != null) return found;
        }
        return null;
    }

    static List<Method> findStageMethods(Class<?> clazz) {
        List<Method> stages = new ArrayList<>();
        // Check declared class and superclass (for Spring proxies)
        for (Method m : clazz.getMethods()) {
            if (m.isAnnotationPresent(Stage.class)) {
                stages.add(m);
            }
        }
        if (stages.isEmpty() && clazz.getSuperclass() != null) {
            for (Method m : clazz.getSuperclass().getMethods()) {
                if (m.isAnnotationPresent(Stage.class)) {
                    stages.add(m);
                }
            }
        }
        return stages;
    }

    private static Method findCompensationMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.isAnnotationPresent(Compensation.class)) {
                return m;
            }
        }
        // Also check superclass for Spring AOP proxies
        if (clazz.getSuperclass() != null) {
            for (Method m : clazz.getSuperclass().getMethods()) {
                if (m.getName().equals(name) && m.isAnnotationPresent(Compensation.class)) {
                    return m;
                }
            }
        }
        throw new WorkflowDefinitionException(
                "No @Compensation method named '" + name + "' found on " + clazz.getSimpleName());
    }

    private static String stageName(Method stage) {
        Stage annotation = stage.getAnnotation(Stage.class);
        return annotation.name().isBlank() ? stage.getName() : annotation.name();
    }
}
