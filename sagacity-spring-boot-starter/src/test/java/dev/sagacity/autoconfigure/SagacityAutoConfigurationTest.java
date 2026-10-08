package dev.sagacity.autoconfigure;

import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.journal.AuditStore;
import dev.sagacity.springai.Sagacity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SagacityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SagacityAutoConfiguration.class));

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SagacityAutoConfiguration.class));

    @Test
    @DisplayName("auto-configuration creates all required beans")
    void autoConfigurationCreatesBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Sagacity.class);
            assertThat(context).hasSingleBean(AuditStore.class);
            assertThat(context).hasSingleBean(ApprovalStore.class);
        });
    }

    @Test
    @DisplayName("auto-configuration is disabled when sagacity.enabled=false")
    void autoConfigurationDisabledWhenPropertySetToFalse() {
        contextRunner
                .withPropertyValues("sagacity.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(Sagacity.class));
    }

    @Test
    @DisplayName("uses JdbcAuditStore when DataSource is available")
    void usesJdbcAuditStoreWhenDataSourceAvailable() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:sagacity_test;MODE=PostgreSQL",
                        "spring.datasource.username=sa")
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditStore.class);
                    assertThat(context.getBean(AuditStore.class))
                            .isInstanceOf(dev.sagacity.core.journal.JdbcAuditStore.class);
                });
    }

    @Test
    @DisplayName("uses durable ApprovalStore when DataSource is available")
    void usesDurableApprovalStoreWhenDataSourceAvailable() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:sagacity_approval_test;MODE=PostgreSQL",
                        "spring.datasource.username=sa")
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(ApprovalStore.class))
                        .isInstanceOf(dev.sagacity.core.approval.PostgresApprovalStore.class));
    }

    @Test
    @DisplayName("uses InMemoryApprovalStore when no DataSource")
    void usesInMemoryApprovalStoreWhenNoDataSource() {
        contextRunner.run(context -> assertThat(context.getBean(ApprovalStore.class))
                .isInstanceOf(dev.sagacity.core.approval.InMemoryApprovalStore.class));
    }

    @Test
    @DisplayName("uses InMemoryAuditStore when no DataSource")
    void usesInMemoryAuditStoreWhenNoDataSource() {
        contextRunner.run(context ->
                assertThat(context.getBean(AuditStore.class))
                        .isInstanceOf(dev.sagacity.core.journal.InMemoryAuditStore.class));
    }

    @Test
    @DisplayName("uses Slf4jAuditStore when sagacity.audit-store=slf4j")
    void usesSlf4jAuditStoreWhenConfigured() {
        contextRunner
                .withPropertyValues("sagacity.audit-store=slf4j")
                .run(context ->
                        assertThat(context.getBean(AuditStore.class))
                                .isInstanceOf(dev.sagacity.core.journal.Slf4jAuditStore.class));
    }

    // ── REST endpoint registration ──────────────────────────────────────────

    @Test
    @DisplayName("approval controller is registered in a servlet web app")
    void approvalControllerIsRegisteredInAServletWebApp() {
        webContextRunner.run(context ->
                assertThat(context).hasSingleBean(SagacityApprovalController.class));
    }

    @Test
    @DisplayName("approval controller is absent when endpoints disabled")
    void approvalControllerIsAbsentWhenEndpointsDisabled() {
        webContextRunner
                .withPropertyValues("sagacity.approval-endpoints-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SagacityApprovalController.class);
                    assertThat(context).hasSingleBean(Sagacity.class);
                });
    }

    @Test
    @DisplayName("approval controller is absent outside a web app")
    void approvalControllerIsAbsentOutsideWebApp() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(SagacityApprovalController.class));
    }

    // ── Cloud store ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("uses CloudAuditStore when api-key is configured")
    void usesCloudAuditStoreWhenApiKeyConfigured() {
        contextRunner
                .withPropertyValues("sagacity.cloud.api-key=test-key-123")
                .run(context ->
                        assertThat(context.getBean(AuditStore.class))
                                .isInstanceOf(dev.sagacity.core.journal.CloudAuditStore.class));
    }

    @Test
    @DisplayName("Cloud store takes priority over JDBC when both configured")
    void cloudStoreTakesPriorityOverJdbc() {
        contextRunner
                .withPropertyValues(
                        "sagacity.cloud.api-key=test-key-123",
                        "spring.datasource.url=jdbc:h2:mem:sagacity_cloud_test;MODE=PostgreSQL",
                        "spring.datasource.username=sa")
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class))
                .run(context ->
                        assertThat(context.getBean(AuditStore.class))
                                .isInstanceOf(dev.sagacity.core.journal.CloudAuditStore.class));
    }

    @Test
    @DisplayName("Cloud store uses custom base URL when provided")
    void cloudStoreUsesCustomBaseUrl() {
        contextRunner
                .withPropertyValues(
                        "sagacity.cloud.api-key=test-key",
                        "sagacity.cloud.base-url=https://staging.sagacity.dev")
                .run(context ->
                        assertThat(context.getBean(AuditStore.class))
                                .isInstanceOf(dev.sagacity.core.journal.CloudAuditStore.class));
    }

    @Test
    @DisplayName("ApprovalStore stays Postgres even when Cloud store selected")
    void approvalStoreRemainsPostgresEvenWhenCloudSelected() {
        contextRunner
                .withPropertyValues(
                        "sagacity.cloud.api-key=test-key-123",
                        "spring.datasource.url=jdbc:h2:mem:sagacity_approval_cloud_test;MODE=PostgreSQL",
                        "spring.datasource.username=sa")
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class))
                .run(context -> {
                    assertThat(context.getBean(AuditStore.class))
                            .isInstanceOf(dev.sagacity.core.journal.CloudAuditStore.class);
                    assertThat(context.getBean(ApprovalStore.class))
                            .isInstanceOf(dev.sagacity.core.approval.PostgresApprovalStore.class);
                });
    }

    @Test
    @DisplayName("Cloud store not used when api-key is blank")
    void cloudStoreNotUsedWhenApiKeyBlank() {
        contextRunner
                .withPropertyValues("sagacity.cloud.api-key=")
                .run(context ->
                        assertThat(context.getBean(AuditStore.class))
                                .isInstanceOf(dev.sagacity.core.journal.InMemoryAuditStore.class));
    }
}
