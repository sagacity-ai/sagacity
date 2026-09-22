package dev.sagacity.workflows.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a workflow stage.
 *
 * <p>Stages are executed in ascending {@link #order} within a {@link Workflow}-annotated
 * class. Each stage receives the output of the previous stage as its first parameter
 * (if the types are compatible). The runtime resolves this by position — the first
 * parameter of stage N is injected with the return value of stage N-1.
 *
 * <h2>Stage chaining</h2>
 * <pre>{@code
 * @Stage(order = 1)
 * public OrderDetails lookupOrder(String orderId) { ... }
 *
 * @Stage(order = 2)
 * @Compensable(by = "cancelReservation")
 * public Reservation reserveInventory(OrderDetails order) {
 *     // 'order' is automatically injected from stage 1's return value
 * }
 * }</pre>
 *
 * <p>If a stage returns {@code void} or the next stage takes no parameters,
 * chaining is skipped and the stage runs independently.
 *
 * <h2>Compensation</h2>
 * <p>Pair with {@link dev.sagacity.core.annotation.Compensable} to declare a
 * rollback action. When the workflow fails at any stage, all earlier stages
 * with compensations are run in reverse order.
 *
 * <h2>Audit</h2>
 * <p>Every stage execution (start, completion, failure) is written to the
 * {@link dev.sagacity.core.journal.SideEffectJournal} with the workflow run ID
 * as the saga ID.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Stage {

    /**
     * Execution order within the workflow. Must be unique within a workflow class.
     * Stages are executed in ascending order — gaps are allowed (e.g. 1, 10, 20).
     */
    int order();

    /**
     * Human-readable name for this stage. Defaults to the method name.
     * Shown in the approval UI, audit trail, and {@link dev.sagacity.workflows.WorkflowHandle}.
     */
    String name() default "";
}
