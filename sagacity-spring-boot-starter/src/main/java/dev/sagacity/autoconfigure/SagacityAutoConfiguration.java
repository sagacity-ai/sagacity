package dev.sagacity.autoconfigure;

import javax.sql.DataSource;

import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.approval.InMemoryApprovalStore;
import dev.sagacity.core.approval.PostgresApprovalStore;
import dev.sagacity.core.journal.CloudSideEffectJournal;
import dev.sagacity.core.journal.InMemorySideEffectJournal;
import dev.sagacity.core.journal.JdbcSideEffectJournal;
import dev.sagacity.core.journal.SideEffectJournal;
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
 * <h2>Journal selection — priority order</h2>
 * <ol>
 *   <li>User-declared {@code SideEffectJournal} bean ({@code @ConditionalOnMissingBean})
 *   <li>{@code sagacity.cloud.api-key} is set → {@link CloudSideEffectJournal}
 *   <li>{@code DataSource} bean is present → {@link JdbcSideEffectJournal}
 *   <li>Fallback → {@link InMemorySideEffectJournal} (dev/testing only)
 * </ol>
 *
 * <h2>ApprovalStore selection</h2>
 * <p>The approval store always prefers Postgres when a {@code DataSource} is
 * present, regardless of which journal is selected. An in-memory store loses
 * pending approvals on restart — stranding any in-flight irreversible tool.
 */
@AutoConfiguration
@EnableConfigurationProperties(SagacityProperties.class)
@ConditionalOnProperty(prefix = "sagacity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SagacityAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public SideEffectJournal sagacityJournal(
			org.springframework.beans.factory.ObjectProvider<DataSource> dataSourceProvider,
			SagacityProperties properties) {

		// Priority 1: Cloud journal when api-key is configured
		SagacityProperties.Cloud cloud = properties.getCloud();
		if (cloud.isConfigured()) {
			String baseUrl = cloud.getBaseUrl();
			return (baseUrl != null && !baseUrl.isBlank())
					? new CloudSideEffectJournal(cloud.getApiKey(), baseUrl)
					: new CloudSideEffectJournal(cloud.getApiKey());
		}

		// Priority 2: JDBC journal — works with PostgreSQL, MySQL, MariaDB, Oracle, H2
		DataSource dataSource = dataSourceProvider.getIfAvailable();
		if (dataSource != null) {
			if (properties.isSchemaInit()) {
				initSchema(dataSource);
			}
			return new JdbcSideEffectJournal(dataSource);
		}

		// Priority 3: In-memory fallback (dev/test only)
		return new InMemorySideEffectJournal();
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
	public Sagacity sagacity(SideEffectJournal journal, ApprovalStore approvalStore,
			SagacityProperties properties) {
		SagacityProperties.Retry retry = properties.getRetry();
		return Sagacity.create(journal, approvalStore,
				retry.getInitialDelayMs(), retry.getBackoffMultiplier());
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

	/**
	 * Embedded UI served at {@code /sagacity/ui}.
	 * Enabled by default when approval endpoints are enabled.
	 * Disable with {@code sagacity.ui-enabled=false}.
	 */
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
			stmt.execute("""
				CREATE TABLE IF NOT EXISTS sagacity_approval_request (
				    saga_id      TEXT        NOT NULL,
				    journal_seq  BIGINT      NOT NULL,
				    tool_name    TEXT        NOT NULL,
				    input        TEXT        NOT NULL DEFAULT '',
				    input_hash   CHAR(64)    NOT NULL,
				    created_at   TIMESTAMP   NOT NULL,
				    PRIMARY KEY (saga_id, journal_seq)
				)
			""");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_approval_saga_id ON sagacity_approval_request (saga_id)");
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize Sagacity schema", e);
		}
	}
}
