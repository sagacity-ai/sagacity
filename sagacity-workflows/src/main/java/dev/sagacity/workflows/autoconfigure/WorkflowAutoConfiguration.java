package dev.sagacity.workflows.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

import dev.sagacity.core.journal.SideEffectJournal;
import dev.sagacity.workflows.WorkflowRuntime;
import dev.sagacity.workflows.WorkflowTopologyValidator;
import dev.sagacity.workflows.web.HumanApprovalGateController;

/**
 * Auto-configuration for the Sagacity workflow engine.
 *
 * <p>Wires:
 * <ul>
 *   <li>{@link WorkflowRuntime} — the execution engine (always)</li>
 *   <li>{@link WorkflowTopologyValidator} — startup validation (always)</li>
 *   <li>{@link HumanApprovalGateController} — REST endpoints (when Spring MVC is present)</li>
 * </ul>
 *
 * <p>Requires a {@link SideEffectJournal} bean from {@code sagacity-spring-boot-starter}.
 */
@AutoConfiguration
public class WorkflowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRuntime workflowRuntime(SideEffectJournal journal, ApplicationContext applicationContext) {
        return new WorkflowRuntime(journal, applicationContext);
    }

    @Bean
    public WorkflowTopologyValidator workflowTopologyValidator() {
        return new WorkflowTopologyValidator();
    }

    @Bean
    @ConditionalOnClass(DispatcherServlet.class)
    @ConditionalOnMissingBean(HumanApprovalGateController.class)
    public HumanApprovalGateController humanApprovalGateController(WorkflowRuntime workflowRuntime) {
        return new HumanApprovalGateController(workflowRuntime);
    }
}
