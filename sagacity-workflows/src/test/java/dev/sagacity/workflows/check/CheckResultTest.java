package dev.sagacity.workflows.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckResultTest {

    @Test
    @DisplayName("pass() returns passed=true with empty reason")
    void pass_isPassedWithEmptyReason() {
        CheckResult result = CheckResult.pass();
        assertThat(result.isPassed()).isTrue();
        assertThat(result.reason()).isEmpty();
    }

    @Test
    @DisplayName("fail(reason) returns passed=false with reason")
    void fail_isFailedWithReason() {
        CheckResult result = CheckResult.fail("budget exceeded");
        assertThat(result.isPassed()).isFalse();
        assertThat(result.reason()).isEqualTo("budget exceeded");
    }

    @Test
    @DisplayName("fail(null) uses default reason")
    void fail_nullReasonUsesDefault() {
        CheckResult result = CheckResult.fail(null);
        assertThat(result.isPassed()).isFalse();
        assertThat(result.reason()).isEqualTo("check failed");
    }
}
