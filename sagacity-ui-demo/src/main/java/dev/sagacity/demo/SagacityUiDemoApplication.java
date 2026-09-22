package dev.sagacity.demo;

import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.workflows.WorkflowHandle;
import dev.sagacity.workflows.WorkflowRuntime;
import dev.sagacity.workflows.annotation.Gate;
import dev.sagacity.workflows.annotation.Stage;
import dev.sagacity.workflows.annotation.Workflow;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Demo app that seeds workflow runs so the embedded UI has real data.
 * No API key required — uses in-memory journal.
 *
 * Access the UI at: http://localhost:8080/sagacity/ui
 */
@SpringBootApplication
public class SagacityUiDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SagacityUiDemoApplication.class, args);
    }

    @Bean
    CommandLineRunner seedWorkflows(WorkflowRuntime runtime,
                                   RefundWorkflow refundWorkflow,
                                   OnboardingWorkflow onboardingWorkflow,
                                   FailingWorkflow failingWorkflow) {
        return args -> {
            System.out.println();
            System.out.println("╔════════════════════════════════════════════════════╗");
            System.out.println("║  Sagacity UI Demo — seeding workflow runs...       ║");
            System.out.println("╚════════════════════════════════════════════════════╝");
            System.out.println();

            // 1. A completed workflow (runs to completion immediately)
            System.out.println("▶ Running onboarding workflow (will complete)...");
            WorkflowHandle completed = runtime.runAsync(onboardingWorkflow, "EMP-001");
            completed.awaitCompletion(10, TimeUnit.SECONDS);
            System.out.println("  ✓ Onboarding workflow completed");

            // 2. A failing workflow (stage 2 throws → compensation runs)
            System.out.println("▶ Running payment workflow (stage 2 will fail)...");
            WorkflowHandle failed = runtime.runAsync(failingWorkflow, "PAY-887");
            failed.awaitCompletion(10, TimeUnit.SECONDS);
            System.out.println("  ✓ Payment workflow failed + compensated");

            // 3. A workflow paused at a gate (stays paused — approve from UI)
            System.out.println("▶ Running refund workflow (will pause at compliance gate)...");
            WorkflowHandle gated = runtime.runAsync(refundWorkflow, "ORD-44210");
            // Wait until it reaches the gate
            long deadline = System.currentTimeMillis() + 5000;
            while (gated.status().name().equals("RUNNING") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            System.out.println("  ⏸ Refund workflow paused at gate — approve it in the UI");

            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println("  Open the Sagacity UI:");
            System.out.println("  → http://localhost:8080/sagacity/ui");
            System.out.println();
            System.out.println("  The refund workflow is waiting for your approval.");
            System.out.println("  Go to the Approvals tab and click Approve.");
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println();
        };
    }
}

// ── Workflow 1: Refund (pauses at compliance gate) ─────────────────────────

@Workflow(value = "refund-approval", description = "Customer refund with compliance gate")
@Component
class RefundWorkflow {

    @Stage(order = 1, name = "validateRefund")
    @Compensable(by = "cancelValidation")
    public String validateRefund(String orderId) {
        System.out.println("    [refund] Stage 1: validateRefund(" + orderId + ")");
        return "val-" + orderId;
    }

    @Stage(order = 2, name = "issueRefund")
    @Compensable(by = "reverseRefund")
    public String issueRefund(String validationId) {
        System.out.println("    [refund] Stage 2: issueRefund(" + validationId + ")");
        return "ref-" + System.currentTimeMillis() % 100000;
    }

    @Stage(order = 3, name = "notifyCompliance")
    @Gate(approvalRequired = true, reason = "Compliance must approve before customer is notified")
    public String notifyCompliance(String refundId) {
        System.out.println("    [refund] Stage 3: notifyCompliance(" + refundId + ") — gate approved!");
        return "cmp-" + refundId;
    }

    @Stage(order = 4, name = "sendConfirmation")
    public void sendConfirmation(String complianceRef) {
        System.out.println("    [refund] Stage 4: sendConfirmation(" + complianceRef + ") — email sent!");
    }

    @Compensation
    public void cancelValidation(CompensationContext ctx) {
        System.out.println("    [refund] Compensate: cancelValidation");
    }

    @Compensation
    public void reverseRefund(CompensationContext ctx) {
        System.out.println("    [refund] Compensate: reverseRefund(" + ctx.result() + ")");
    }
}

// ── Workflow 2: Onboarding (completes successfully) ────────────────────────

@Workflow(value = "employee-onboarding", description = "New hire onboarding pipeline")
@Component
class OnboardingWorkflow {

    @Stage(order = 1, name = "createADAccount")
    @Compensable(by = "deleteADAccount")
    public String createADAccount(String employeeId) {
        System.out.println("    [onboarding] Stage 1: createADAccount(" + employeeId + ")");
        return "ad-" + employeeId.toLowerCase();
    }

    @Stage(order = 2, name = "provisionSlack")
    @Compensable(by = "deprovisionSlack")
    public String provisionSlack(String adAccount) {
        System.out.println("    [onboarding] Stage 2: provisionSlack(" + adAccount + ")");
        return "slack-" + adAccount;
    }

    @Stage(order = 3, name = "enrollPayroll")
    public void enrollPayroll(String slackId) {
        System.out.println("    [onboarding] Stage 3: enrollPayroll(" + slackId + ") — done");
    }

    @Compensation
    public void deleteADAccount(CompensationContext ctx) {
        System.out.println("    [onboarding] Compensate: deleteADAccount");
    }

    @Compensation
    public void deprovisionSlack(CompensationContext ctx) {
        System.out.println("    [onboarding] Compensate: deprovisionSlack");
    }
}

// ── Workflow 3: Payment (stage 2 fails → compensation runs) ───────────────

@Workflow(value = "payment-processing", description = "Payment order: reserve → charge → dispatch")
@Component
class FailingWorkflow {

    @Stage(order = 1, name = "reserveInventory")
    @Compensable(by = "releaseInventory")
    public String reserveInventory(String orderId) {
        System.out.println("    [payment] Stage 1: reserveInventory(" + orderId + ")");
        return "res-" + orderId;
    }

    @Stage(order = 2, name = "chargeCard")
    @Compensable(by = "voidCharge")
    public String chargeCard(String reservationId) {
        System.out.println("    [payment] Stage 2: chargeCard(" + reservationId + ") — card declined!");
        throw new RuntimeException("Card declined — insufficient funds");
    }

    @Stage(order = 3, name = "dispatch")
    public void dispatch(String chargeId) {
        System.out.println("    [payment] Stage 3: dispatch — should not reach here");
    }

    @Compensation
    public void voidCharge(CompensationContext ctx) {
        System.out.println("    [payment] Compensate: voidCharge (charge never went through)");
    }

    @Compensation
    public void releaseInventory(CompensationContext ctx) {
        System.out.println("    [payment] Compensate: releaseInventory(" + ctx.result() + ")");
    }
}
