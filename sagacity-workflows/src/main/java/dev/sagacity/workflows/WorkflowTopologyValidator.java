package dev.sagacity.workflows;

import java.util.Map;
import java.util.logging.Logger;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import dev.sagacity.workflows.annotation.Workflow;

/**
 * Validates all {@link Workflow}-annotated beans in the application context on
 * startup (after {@link ContextRefreshedEvent}).
 *
 * <p>This is inspired by Atomic's stage topology validation which fails fast at
 * startup rather than at runtime. A misconfigured workflow — duplicate stage orders,
 * missing compensation method — fails the application startup instead of silently
 * producing a broken run at 2am.
 *
 * <p>Auto-registered by {@link dev.sagacity.workflows.autoconfigure.WorkflowAutoConfiguration}.
 */
public class WorkflowTopologyValidator implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = Logger.getLogger(WorkflowTopologyValidator.class.getName());

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        Map<String, Object> workflowBeans = ctx.getBeansWithAnnotation(Workflow.class);
        if (workflowBeans.isEmpty()) {
            return;
        }
        log.info("[sagacity-workflows] validating " + workflowBeans.size() + " workflow(s) at startup");
        for (Map.Entry<String, Object> entry : workflowBeans.entrySet()) {
            try {
                WorkflowRuntime.validateTopology(entry.getValue());
                Workflow annotation = entry.getValue().getClass().getAnnotation(Workflow.class);
                if (annotation == null && entry.getValue().getClass().getSuperclass() != null) {
                    annotation = entry.getValue().getClass().getSuperclass().getAnnotation(Workflow.class);
                }
                String name = annotation != null ? annotation.value() : entry.getKey();
                log.info("[sagacity-workflows] topology OK: " + name);
            } catch (WorkflowDefinitionException ex) {
                // Re-throw with the bean name for context
                throw new WorkflowDefinitionException(
                        "Invalid workflow bean '" + entry.getKey() + "': " + ex.getMessage(), ex);
            }
        }
    }
}
