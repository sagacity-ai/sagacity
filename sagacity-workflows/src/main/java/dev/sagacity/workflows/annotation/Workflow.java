package dev.sagacity.workflows.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Sagacity workflow definition.
 *
 * <p>A workflow is an ordered sequence of {@link Stage} methods executed by
 * {@link dev.sagacity.workflows.WorkflowRuntime}. If any stage fails, all
 * previously completed stages with declared {@link dev.sagacity.core.annotation.Compensable}
 * methods are compensated in reverse order.
 *
 * <h2>Minimal example</h2>
 * <pre>{@code
 * @Workflow("refund-request")
 * @Component
 * public class RefundWorkflow {
 *
 *     @Stage(order = 1)
 *     @Compensable(by = "cancelRefund")
 *     public RefundConfirmation issueRefund(String orderId) { ... }
 *
 *     @Stage(order = 2)
 *     @Gate(approvalRequired = true)
 *     public void notifyCustomer(RefundConfirmation refund) { ... }
 *
 *     @Compensation
 *     public void cancelRefund(CompensationContext ctx) { ... }
 * }
 * }</pre>
 *
 * <p>The class must be a Spring bean (annotated with {@code @Component},
 * {@code @Service}, etc.) so the AOP interceptor can wrap it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Workflow {

    /**
     * Logical name of this workflow. Used in audit trail entries, REST endpoints,
     * and {@link dev.sagacity.workflows.WorkflowHandle} status reporting.
     * Must be unique within the application context.
     */
    String value();

    /**
     * Human-readable description shown in the approval UI and audit exports.
     * Optional.
     */
    String description() default "";
}
