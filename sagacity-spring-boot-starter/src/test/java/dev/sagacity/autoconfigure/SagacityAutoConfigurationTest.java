package dev.sagacity.autoconfigure;

import dev.sagacity.core.approval.ApprovalStore;
import dev.sagacity.core.journal.SideEffectJournal;
import dev.sagacity.springai.Sagacity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SagacityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
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
                        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
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
}
