package dev.sagacity.autoconfigure;

import javax.sql.DataSource;

import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.approval.InMemoryApprovalStore;
import dev.sagacity.core.approval.PostgresApprovalStore;
import dev.sagacity.core.journal.AuditStore;
import dev.sagacity.core.journal.InMemoryAuditStore;
import dev.sagacity.core.journal.JdbcAuditStore;
import dev.sagacity.core.journal.Slf4jAuditStore;
import dev.sagacity.springai.Sagacity;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Sagacity.
 *
 * <h2>AuditStore selection — priority order</h2>
 * <ol>
 *   <li>User-declared {@code AuditStore} bean ({@code @ConditionalOnMissingBean})
 *   <li>{@code sagacity.audit-store=slf4j} → {@link Slf4jAuditStore} (zero-infra dev mode)
 *   <li>{@code DataSource} bean present → {@link JdbcAuditStore}
 *   <li>Fallback → {@link InMemoryAuditStore} (dev/testing only)
 * </ol>
 */
@AutoConfiguration
@EnableConfigurationProperties(SagacityProperties.class)
@ConditionalOnProperty(prefix = "sagacity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SagacityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditStore sagacityAuditStore(
            org.springframework.beans.factory.ObjectProvider<DataSource> dataSourceProvider,
            SagacityProperties properties) {

        // Priority 1: Explicit Slf4j store (zero-infra dev mode)
        if ("slf4j".equalsIgnoreCase(properties.getAuditStore())) {
            return new Slf4jAuditStore();
        }

        // Priority 2: JDBC store
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource != null) {
            if (properties.isSchemaInit()) {
                initSchema(dataSource);
            }
            return new JdbcAuditStore(dataSource);
        }

        // Priority 3: In-memory fallback
        return new InMemoryAuditStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalStore sagacityApprovalStore(
            org.springframework.beans.factory.ObjectProvider<DataSource> dataSourceProvider,
            SagacityProperties properties) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return new InMemoryApprovalStore();
        }
        if (properties.isSchemaInit()) {
            initSchema(dataSource);
        }
        return new PostgresApprovalStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public Sagacity sagacity(AuditStore auditStore, ApprovalStore approvalStore,
            SagacityProperties properties) {
        SagacityProperties.Retry retry = properties.getRetry();
        return Sagacity.create(auditStore, approvalStore,
                retry.getInitialDelayMs(), retry.getBackoffMultiplier());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "sagacity", name = "approval-endpoints-enabled",
            havingValue = "true", matchIfMissing = true)
    public SagacityApprovalController sagacityApprovalController(Sagacity sagacity) {
        return new SagacityApprovalController(sagacity);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "sagacity", name = "ui-enabled",
            havingValue = "true", matchIfMissing = true)
    public SagacityUiController sagacityUiController() {
        return new SagacityUiController();
    }

    private void initSchema(DataSource dataSource) {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sagacity_journal (
                    saga_id     TEXT      NOT NULL,
                    seq         BIGINT    NOT NULL,
                    tool_name   TEXT      NOT NULL,
                    phase       TEXT      NOT NULL,
                    phase_data  TEXT      NOT NULL DEFAULT '{}',
                    input       TEXT      NOT NULL DEFAULT '',
                    recorded_at TIMESTAMP NOT NULL,
                    hash        CHAR(64)  NOT NULL,
                    PRIMARY KEY (saga_id, seq)
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_sagacity_journal_saga_id
                    ON sagacity_journal (saga_id)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_sagacity_journal_phase
                    ON sagacity_journal (saga_id, phase)
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sagacity_approval_request (
                    saga_id      TEXT      NOT NULL,
                    journal_seq  BIGINT    NOT NULL,
                    tool_name    TEXT      NOT NULL,
                    input        TEXT      NOT NULL DEFAULT '',
                    input_hash   CHAR(64)  NOT NULL,
                    created_at   TIMESTAMP NOT NULL,
                    PRIMARY KEY (saga_id, journal_seq)
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_sagacity_approval_saga_id
                    ON sagacity_approval_request (saga_id)
                """);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Sagacity schema", e);
        }
    }
}
