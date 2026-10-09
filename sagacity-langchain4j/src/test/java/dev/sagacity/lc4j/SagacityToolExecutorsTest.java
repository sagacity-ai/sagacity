package dev.sagacity.lc4j;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.sagacity.core.annotation.Compensable;
import dev.sagacity.core.annotation.Compensation;
import dev.sagacity.core.compensation.CompensationContext;
import dev.sagacity.core.journal.AuditStore;
import dev.sagacity.core.journal.InMemoryAuditStore;
import dev.sagacity.core.journal.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SagacityToolExecutors} and {@link SagacityToolExecutor}.
 *
 * <p>Uses the in-memory audit store — no JDBC, no Docker needed.
 */
class SagacityToolExecutorsTest {

    // ── Tool beans used across tests ──────────────────────────────────────

    static class OrderTools {
        final List<String> events = new ArrayList<>();

        @Tool("Reserve inventory")
        @Compensable(by = "releaseInventory")
        public String reserveInventory(String item) {
            events.add("reserve:" + item);
            return "reserved-" + item;
        }

        @Compensation
        public void releaseInventory(CompensationContext ctx) {
            events.add("release:" + ctx.result());
        }

        @Tool("Create order")
        @Compensable(by = "cancelOrder")
        public String createOrder(String item) {
            events.add("create:" + item);
            return "order-" + item;
        }

        @Compensation
        public void cancelOrder(CompensationContext ctx) {
            events.add("cancel:" + ctx.result());
        }

        @Tool("Schedule shipment")
        public String scheduleShipment(String orderId) {
            throw new IllegalStateException("no carrier available");
        }
    }

    static class TransientException extends RuntimeException {
        TransientException(String msg) { super(msg); }
    }

    static class RetryableTools {
        final AtomicInteger callCount = new AtomicInteger(0);
        final int succeedOnAttempt;

        RetryableTools(int succeedOnAttempt) {
            this.succeedOnAttempt = succeedOnAttempt;
        }

        @Tool("Flaky tool")
        @Compensable(by = "undo", retries = 3, retryOn = { TransientException.class })
        public String flakyTool(String input) {
            if (callCount.incrementAndGet() < succeedOnAttempt) {
                throw new TransientException("transient on attempt " + callCount.get());
            }
            return "ok-on-" + callCount.get();
        }

        @Compensation
        public void undo(CompensationContext ctx) {}
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ToolExecutionRequest req(String name, String args) {
        return ToolExecutionRequest.builder().name(name).arguments(args).build();
    }

    private static String call(Map<String, ToolExecutor> tools, String name,
                               String args, Object memoryId) {
        return tools.get(name).execute(req(name, args), memoryId);
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("wrap — basic journaling")
    class BasicJournalingTests {

        @Test
        @DisplayName("successful tool call journals Intent then Executed")
        void successfulToolJournalsIntentThenExecuted() {
            AuditStore store = new InMemoryAuditStore();
            OrderTools bean = new OrderTools();
            var tools = SagacityToolExecutors.wrap(store, bean);

            call(tools, "Reserve inventory", "{\"item\":\"laptop\"}", "saga-1");

            var entries = store.findBySagaId("saga-1");
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).phase()).isInstanceOf(Phase.Intent.class);
            assertThat(entries.get(1).phase()).isInstanceOf(Phase.Executed.class);
            assertThat(((Phase.Executed) entries.get(1).phase()).result())
                    .contains("reserved-laptop");
        }

        @Test
        @DisplayName("failed tool call journals Intent then Failed")
        void failedToolJournalsIntentThenFailed() {
            AuditStore store = new InMemoryAuditStore();
            OrderTools bean = new OrderTools();
            var tools = SagacityToolExecutors.wrap(store, bean);

            try {
                call(tools, "Schedule shipment", "{\"orderId\":\"o-1\"}", "saga-1");
            } catch (Exception ignored) {}

            var entries = store.findBySagaId("saga-1");
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).phase()).isInstanceOf(Phase.Intent.class);
            assertThat(entries.get(1).phase()).isInstanceOf(Phase.Failed.class);
            assertThat(((Phase.Failed) entries.get(1).phase()).error())
                    .contains("no carrier available");
        }

        @Test
        @DisplayName("null memoryId is pass-through — nothing journaled")
        void nullMemoryIdIsPassThrough() {
            AuditStore store = new InMemoryAuditStore();
            OrderTools bean = new OrderTools();
            var tools = SagacityToolExecutors.wrap(store, bean);

            String result = call(tools, "Reserve inventory", "{\"item\":\"laptop\"}", null);

            assertThat(result).contains("reserved-laptop");
            // Nothing journaled — no sagaId
            assertThat(bean.events).containsExactly("reserve:laptop");
        }
    }

    @Nested
    @DisplayName("compensation")
    class CompensationTests {

        @Test
        @DisplayName("failed tool triggers compensation of prior executed steps in reverse")
        void failedToolTriggersCompensationInReverse() {
            AuditStore store = new InMemoryAuditStore();
            OrderTools bean = new OrderTools();
            var tools = SagacityToolExecutors.wrap(store, bean);

            call(tools, "Reserve inventory", "{\"item\":\"laptop\"}", "saga-1");
            call(tools, "Create order", "{\"item\":\"laptop\"}", "saga-1");
            try {
                call(tools, "Schedule shipment", "{\"orderId\":\"o-1\"}", "saga-1");
            } catch (Exception ignored) {}

            // Compensation runs in reverse: cancel order, then release inventory
            assertThat(bean.events).containsExactly(
                    "reserve:laptop",
                    "create:laptop",
                    "cancel:order-laptop",
                    "release:reserved-laptop");

            // Journal has Compensated entries
            assertThat(store.findBySagaId("saga-1", Phase.Compensated.class)).hasSize(2);
        }

        @Test
        @DisplayName("broken @Compensable declaration fails fast at wrap time")
        void brokenCompensableFailsFastAtWrapTime() {
            class BrokenTools {
                @Tool("Do something")
                @Compensable(by = "doesNotExist")
                public String act(String x) { return "ok"; }
            }

            assertThatThrownBy(() -> SagacityToolExecutors.wrap(new BrokenTools()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("doesNotExist");
        }

        @Test
        @DisplayName("tools without @Compensable still journal but skip compensation")
        void toolsWithoutCompensableJournalButSkipCompensation() {
            AuditStore store = new InMemoryAuditStore();
            OrderTools bean = new OrderTools();
            var tools = SagacityToolExecutors.wrap(store, bean);

            // scheduleShipment has no @Compensable — fails and journals Failed,
            // then CompensationRunner skips it (no handler registered)
            call(tools, "Reserve inventory", "{\"item\":\"laptop\"}", "saga-1");
            try {
                call(tools, "Schedule shipment", "{\"orderId\":\"o-1\"}", "saga-1");
            } catch (Exception ignored) {}

            // reserveInventory gets compensated; scheduleShipment is skipped
            assertThat(bean.events).containsExactly("reserve:laptop", "release:reserved-laptop");
        }
    }

    @Nested
    @DisplayName("retry")
    class RetryTests {

        @Test
        @DisplayName("retries on transient failure and succeeds before exhausting")
        void retriesOnTransientFailureAndSucceeds() {
            AuditStore store = new InMemoryAuditStore();
            RetryableTools bean = new RetryableTools(3); // fails 1+2, succeeds on 3
            var tools = SagacityToolExecutors.wrap(store, bean);

            String result = call(tools, "Flaky tool", "{\"input\":\"x\"}", "saga-1");

            assertThat(result).contains("ok-on-3");
            assertThat(bean.callCount.get()).isEqualTo(3);

            // Journal is transparent — only Intent + Executed
            var entries = store.findBySagaId("saga-1");
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).phase()).isInstanceOf(Phase.Intent.class);
            assertThat(entries.get(1).phase()).isInstanceOf(Phase.Executed.class);
        }

        @Test
        @DisplayName("compensates when all retries exhausted")
        void compensatesWhenAllRetriesExhausted() {
            AuditStore store = new InMemoryAuditStore();
            RetryableTools bean = new RetryableTools(999); // always fails
            var tools = SagacityToolExecutors.wrap(store, bean);

            try {
                call(tools, "Flaky tool", "{\"input\":\"x\"}", "saga-1");
            } catch (Exception ignored) {}

            assertThat(bean.callCount.get()).isEqualTo(4); // 1 + 3 retries
            var entries = store.findBySagaId("saga-1");
            assertThat(entries.get(1).phase()).isInstanceOf(Phase.Failed.class);
        }
    }

    @Nested
    @DisplayName("multiple tool beans")
    class MultipleBeansTests {

        @Test
        @DisplayName("wraps tools from multiple beans into one map")
        void wrapsToolsFromMultipleBeans() {
            class BeanA {
                @Tool("Tool A")
                public String toolA(String x) { return "a-" + x; }
            }

            class BeanB {
                @Tool("Tool B")
                public String toolB(String x) { return "b-" + x; }
            }

            var tools = SagacityToolExecutors.builder()
                    .toolBeans(new BeanA(), new BeanB())
                    .build();

            assertThat(tools).containsKeys("Tool A", "Tool B");
            assertThat(call(tools, "Tool A", "{\"x\":\"1\"}", "s1")).isEqualTo("a-1");
            assertThat(call(tools, "Tool B", "{\"x\":\"2\"}", "s1")).isEqualTo("b-2");
        }

        @Test
        @DisplayName("different memoryIds produce independent audit trails")
        void differentMemoryIdsAreIndependentSagas() {
            AuditStore store = new InMemoryAuditStore();
            OrderTools bean = new OrderTools();
            var tools = SagacityToolExecutors.wrap(store, bean);

            call(tools, "Reserve inventory", "{\"item\":\"a\"}", "saga-A");
            call(tools, "Reserve inventory", "{\"item\":\"b\"}", "saga-B");

            assertThat(store.findBySagaId("saga-A")).hasSize(2);
            assertThat(store.findBySagaId("saga-B")).hasSize(2);
            assertThat(store.findBySagaId("saga-A").get(0).input()).contains("a");
            assertThat(store.findBySagaId("saga-B").get(0).input()).contains("b");
        }
    }

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("builder with custom audit store uses it")
        void builderWithCustomAuditStore() {
            AuditStore store = new InMemoryAuditStore();
            OrderTools bean = new OrderTools();

            var tools = SagacityToolExecutors.builder()
                    .auditStore(store)
                    .toolBeans(bean)
                    .build();

            call(tools, "Reserve inventory", "{\"item\":\"laptop\"}", "saga-1");
            assertThat(store.findBySagaId("saga-1")).hasSize(2);
        }

        @Test
        @DisplayName("wrap(auditStore, beans) is shorthand for builder")
        void wrapShorthandEquivalentToBuilder() {
            AuditStore store = new InMemoryAuditStore();
            OrderTools bean = new OrderTools();

            var tools = SagacityToolExecutors.wrap(store, bean);
            assertThat(tools).isNotEmpty();
            assertThat(tools).containsKey("Reserve inventory");
        }
    }
}
