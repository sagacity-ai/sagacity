package dev.sagacity.autoconfigure;

import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.journal.SideEffectJournal;
import dev.sagacity.springai.Sagacity;

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
    void autoConfigurationCreatesBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Sagacity.class);
            assertThat(context).hasSingleBean(SideEffectJournal.class);
            assertThat(context).hasSingleBean(ApprovalStore.class);
        });
    }

    @Test
    void autoConfigurationDisabledWhenPropertySetToFalse() {
        contextRunner
                .withPropertyValues("sagacity.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(Sagacity.class);
                });
    }

    @Test
    void usesPostgresJournalWhenDataSourceAvailable() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:sagacity_test;MODE=PostgreSQL",
                        "spring.datasource.username=sa"
                )
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class
                ))
                .run(context -> {
                    assertThat(context).hasSingleBean(SideEffectJournal.class);
                    assertThat(context.getBean(SideEffectJournal.class))
                            .isInstanceOf(dev.sagacity.core.journal.PostgresSideEffectJournal.class);
                });
    }

    @Test
    void usesInMemoryJournalWhenNoDataSource() {
        contextRunner.run(context -> {
            assertThat(context.getBean(SideEffectJournal.class))
                    .isInstanceOf(dev.sagacity.core.journal.InMemorySideEffectJournal.class);
        });
    }

    // ── REST endpoint registration ─────────────────────────────────────────

    /**
     * The controller carries {@code @RestController}, but that stereotype only
     * matters if something scans the package — and {@code dev.sagacity.autoconfigure}
     * is not on a consuming application's scan path. Unless auto-configuration
     * declares it as a bean, every documented /sagacity endpoint 404s.
     * Constructing the controller directly in a test cannot catch that.
     */
    @Test
    void approvalControllerIsRegisteredInAServletWebApp() {
        webContextRunner.run(context ->
                assertThat(context).hasSingleBean(SagacityApprovalController.class));
    }

    @Test
    void approvalControllerIsAbsentWhenEndpointsDisabled() {
        webContextRunner
                .withPropertyValues("sagacity.approval-endpoints-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SagacityApprovalController.class);
                    // The library still works — only the REST surface is gone.
                    assertThat(context).hasSingleBean(Sagacity.class);
                });
    }

    @Test
    void approvalControllerIsAbsentOutsideAWebApp() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(SagacityApprovalController.class));
    }
}
