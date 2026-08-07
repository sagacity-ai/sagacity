package dev.sagacity.autoconfigure;

import javax.sql.DataSource;

import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.approval.InMemoryApprovalStore;
import dev.sagacity.core.journal.InMemorySideEffectJournal;
import dev.sagacity.core.journal.PostgresSideEffectJournal;
import dev.sagacity.core.journal.SideEffectJournal;
import dev.sagacity.springai.Sagacity;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Sagacity. Creates the journal, approval store,
 * and Sagacity facade beans automatically.
 */
@AutoConfiguration
@EnableConfigurationProperties(SagacityProperties.class)
@ConditionalOnProperty(prefix = "sagacity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SagacityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApprovalStore sagacityApprovalStore() {
        return new InMemoryApprovalStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SideEffectJournal sagacityJournal(org.springframework.beans.factory.ObjectProvider<DataSource> dataSourceProvider,
            SagacityProperties properties) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource != null) {
            PostgresSideEffectJournal journal = new PostgresSideEffectJournal(dataSource);
            if (properties.isSchemaInit()) {
                initSchema(dataSource);
            }
            return journal;
        }
        return new InMemorySideEffectJournal();
    }

    @Bean
    @ConditionalOnMissingBean
    public Sagacity sagacity(SideEffectJournal journal, ApprovalStore approvalStore) {
        return Sagacity.create(journal, approvalStore);
    }

    /**
     * Registers the approval REST endpoints.
     *
     * <p>The controller must be declared here rather than relying on its own
     * {@code @RestController} stereotype: {@code dev.sagacity.autoconfigure} is
     * not on a consuming application's component-scan path, so nothing would
     * ever instantiate it and every documented {@code /sagacity/**} endpoint
     * would 404.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "sagacity", name = "approval-endpoints-enabled",
            havingValue = "true", matchIfMissing = true)
    public SagacityApprovalController sagacityApprovalController(Sagacity sagacity) {
        return new SagacityApprovalController(sagacity);
    }

    private void initSchema(DataSource dataSource) {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS side_effect_journal (
                    saga_id     TEXT        NOT NULL,
                    seq         BIGINT      NOT NULL,
                    tool_name   TEXT        NOT NULL,
                    phase       TEXT        NOT NULL,
                    input       TEXT        NOT NULL DEFAULT '',
                    payload     TEXT        NOT NULL DEFAULT '',
                    timestamp   TIMESTAMP   NOT NULL,
                    hash        CHAR(64)    NOT NULL,
                    PRIMARY KEY (saga_id, seq)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_journal_saga_id ON side_effect_journal (saga_id)");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Sagacity schema", e);
        }
    }
}
