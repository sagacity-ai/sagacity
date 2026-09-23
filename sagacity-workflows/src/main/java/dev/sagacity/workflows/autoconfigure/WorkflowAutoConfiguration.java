package dev.sagacity.workflows.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sagacity.core.journal.SideEffectJournal;
import dev.sagacity.workflows.WorkflowRuntime;
import dev.sagacity.workflows.WorkflowTopologyValidator;
import dev.sagacity.workflows.store.InMemoryWorkflowRunStore;
import dev.sagacity.workflows.store.JdbcWorkflowRunStore;
import dev.sagacity.workflows.store.WorkflowRunStore;
import dev.sagacity.workflows.web.HumanApprovalGateController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

import javax.sql.DataSource;

/**
 * Auto-configuration for the Sagacity workflow engine.
 *
 * <p>Wires:
 * <ul>
 *   <li>{@link WorkflowRunStore} — JDBC-backed when a {@code DataSource} bean is present,
 *       in-memory otherwise</li>
 *   <li>{@link WorkflowRuntime} — the execution engine (always)</li>
 *   <li>{@link WorkflowTopologyValidator} — startup validation (always)</li>
 *   <li>{@link HumanApprovalGateController} — REST endpoints (when Spring MVC is present)</li>
 * </ul>
 *
 * <p>Requires a {@link SideEffectJournal} bean from {@code sagacity-spring-boot-starter}.
 */
@AutoConfiguration
public class WorkflowAutoConfiguration {

    // -------------------------------------------------------------------------
    // WorkflowRunStore — JDBC when DataSource present, in-memory otherwise
    // -------------------------------------------------------------------------

    /**
     * JDBC-backed store — registered when a {@link DataSource} bean is present.
     * Reuses the app's configured {@link ObjectMapper} if available so custom
     * serializers (e.g. JavaTimeModule) work correctly for stage output types.
     */
    @Bean
    @ConditionalOnMissingBean(WorkflowRunStore.class)
    @ConditionalOnClass(DataSource.class)
    public WorkflowRunStore jdbcWorkflowRunStore(DataSource dataSource,
                                                  ObjectMapper objectMapper) {
        return new JdbcWorkflowRunStore(dataSource, objectMapper);
    }

    /**
     * Fallback — in-memory store when no {@link DataSource} is configured.
     * Runs are lost on JVM restart. Suitable for development and testing.
     */
    @Bean
    @ConditionalOnMissingBean(WorkflowRunStore.class)
    public WorkflowRunStore inMemoryWorkflowRunStore() {
        return new InMemoryWorkflowRunStore();
    }

    // -------------------------------------------------------------------------
    // WorkflowRuntime
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRuntime workflowRuntime(SideEffectJournal journal,
                                           ApplicationContext applicationContext,
                                           WorkflowRunStore workflowRunStore) {
        return new WorkflowRuntime(journal, applicationContext, workflowRunStore);
    }

    // -------------------------------------------------------------------------
    // Topology validator + REST controller
    // -------------------------------------------------------------------------

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
