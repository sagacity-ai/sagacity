package dev.sagacity.workflows.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import dev.sagacity.workflows.check.StageCheck;

/**
 * Declares one or more pre-flight checks that must pass before a {@link Stage} executes.
 *
 * <p>Checks run synchronously before the stage method is called. If any check fails,
 * the workflow does not enter the stage — it transitions to
 * {@link dev.sagacity.workflows.WorkflowStatus#FAILED} and runs compensation for all
 * previously completed stages. The stage itself is never journaled as EXECUTED.
 *
 * <p>This is the right place for: precondition validation, budget/quota enforcement,
 * Jev risk scoring thresholds, or any guard that should block execution before it
 * causes a side effect.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Stage(order = 3)
 * @Check(BudgetCheck.class)
 * @Compensable(by = "revertCharge")
 * public ChargeReceipt chargeCard(PaymentDetails payment) { ... }
 * }</pre>
 *
 * <p>Implement {@link StageCheck} to write a check:
 * <pre>{@code
 * @Component
 * public class BudgetCheck implements StageCheck {
 *     public CheckResult check(StageCheckContext ctx) {
 *         if (ctx.estimatedCost() > budget.remaining()) {
 *             return CheckResult.fail("Budget exceeded: " + ctx.estimatedCost());
 *         }
 *         return CheckResult.pass();
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Check {

    /**
     * One or more {@link StageCheck} implementations to run before this stage.
     * All checks are run in declaration order. The first failure stops the workflow.
     * Check implementations must be Spring beans.
     */
    Class<? extends StageCheck>[] value();
}
