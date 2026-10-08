package dev.sagacity.core.journal;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloudJournalSerializerTest {

    private final CloudJournalSerializer serializer = new CloudJournalSerializer();

    @Nested
    @DisplayName("serializeAppendRequest")
    class SerializeTests {

        @Test
        @DisplayName("produces valid JSON with all required fields")
        void producesValidJson() {
            String json = serializer.serializeAppendRequest(
                    "saga-1", "reserveInventory", new Phase.Intent(), "{\"qty\":5}");

            assertThat(json).contains("\"sagaId\":\"saga-1\"");
            assertThat(json).contains("\"toolName\":\"reserveInventory\"");
            assertThat(json).contains("\"phase\":\"Intent\"");
            assertThat(json).contains("\"phaseData\":{}");
            assertThat(json).contains("\"input\":\"{\\\"qty\\\":5}\"");
        }

        @Test
        @DisplayName("Executed phase includes result in phaseData")
        void executedPhaseIncludesResult() {
            String json = serializer.serializeAppendRequest(
                    "s1", "chargeCard", new Phase.Executed("tx-99"), "input");

            assertThat(json).contains("\"phase\":\"Executed\"");
            assertThat(json).contains("\"phaseData\":{\"result\":\"tx-99\"}");
        }

        @Test
        @DisplayName("Failed phase includes error in phaseData")
        void failedPhaseIncludesError() {
            String json = serializer.serializeAppendRequest(
                    "s1", "tool", new Phase.Failed("timeout"), "input");

            assertThat(json).contains("\"phase\":\"Failed\"");
            assertThat(json).contains("\"phaseData\":{\"error\":\"timeout\"}");
        }

        @Test
        @DisplayName("escapes special characters in input")
        void escapesSpecialCharacters() {
            String json = serializer.serializeAppendRequest(
                    "s1", "tool", new Phase.Intent(), "line1\nline2");

            assertThat(json).contains("\"input\":\"line1\\nline2\"");
        }
    }

    @Nested
    @DisplayName("deserializeEntry")
    class DeserializeEntryTests {

        @Test
        @DisplayName("round-trips an Intent entry")
        void roundTripsIntentEntry() {
            String json = "{\"sagaId\":\"s1\",\"seq\":1,\"toolName\":\"tool\","
                    + "\"phase\":\"Intent\",\"phaseData\":{},"
                    + "\"input\":\"in\",\"timestamp\":\"2026-07-21T10:00:00Z\",\"hash\":\"abc123\"}";

            AuditEntry entry = serializer.deserializeEntry(json);

            assertThat(entry.sagaId()).isEqualTo("s1");
            assertThat(entry.seq()).isEqualTo(1);
            assertThat(entry.toolName()).isEqualTo("tool");
            assertThat(entry.phase()).isInstanceOf(Phase.Intent.class);
            assertThat(entry.input()).isEqualTo("in");
            assertThat(entry.hash()).isEqualTo("abc123");
        }

        @Test
        @DisplayName("round-trips an Executed entry with result")
        void roundTripsExecutedEntry() {
            String json = "{\"sagaId\":\"s1\",\"seq\":2,\"toolName\":\"tool\","
                    + "\"phase\":\"Executed\",\"phaseData\":{\"result\":\"ok\"},"
                    + "\"input\":\"in\",\"timestamp\":\"2026-07-21T10:00:01Z\",\"hash\":\"def456\"}";

            AuditEntry entry = serializer.deserializeEntry(json);

            assertThat(entry.phase()).isInstanceOf(Phase.Executed.class);
            assertThat(((Phase.Executed) entry.phase()).result()).isEqualTo("ok");
        }

        @Test
        @DisplayName("round-trips a Failed entry with error")
        void roundTripsFailedEntry() {
            String json = "{\"sagaId\":\"s1\",\"seq\":3,\"toolName\":\"tool\","
                    + "\"phase\":\"Failed\",\"phaseData\":{\"error\":\"timeout\"},"
                    + "\"input\":\"in\",\"timestamp\":\"2026-07-21T10:00:02Z\",\"hash\":\"ghi789\"}";

            AuditEntry entry = serializer.deserializeEntry(json);

            assertThat(entry.phase()).isInstanceOf(Phase.Failed.class);
            assertThat(((Phase.Failed) entry.phase()).error()).isEqualTo("timeout");
        }
    }

    @Nested
    @DisplayName("deserializeEntries")
    class DeserializeEntriesTests {

        @Test
        @DisplayName("deserializes empty entries array")
        void deserializesEmptyArray() {
            List<AuditEntry> entries = serializer.deserializeEntries("{\"entries\":[]}");
            assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("deserializes multiple entries")
        void deserializesMultipleEntries() {
            String json = "{\"entries\":["
                    + "{\"sagaId\":\"s1\",\"seq\":1,\"toolName\":\"tool\","
                    + "\"phase\":\"Intent\",\"phaseData\":{},"
                    + "\"input\":\"in\",\"timestamp\":\"2026-07-21T10:00:00Z\",\"hash\":\"h1\"},"
                    + "{\"sagaId\":\"s1\",\"seq\":2,\"toolName\":\"tool\","
                    + "\"phase\":\"Executed\",\"phaseData\":{\"result\":\"ok\"},"
                    + "\"input\":\"in\",\"timestamp\":\"2026-07-21T10:00:01Z\",\"hash\":\"h2\"}"
                    + "]}";

            List<AuditEntry> entries = serializer.deserializeEntries(json);

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).phase()).isInstanceOf(Phase.Intent.class);
            assertThat(entries.get(1).phase()).isInstanceOf(Phase.Executed.class);
        }
    }

    @Nested
    @DisplayName("jsonString helper")
    class JsonStringTests {

        @Test
        @DisplayName("wraps value in double quotes")
        void wrapsInQuotes() {
            assertThat(CloudJournalSerializer.jsonString("hello")).isEqualTo("\"hello\"");
        }

        @Test
        @DisplayName("returns null literal for null input")
        void returnsNullLiteralForNull() {
            assertThat(CloudJournalSerializer.jsonString(null)).isEqualTo("null");
        }

        @Test
        @DisplayName("escapes double quotes")
        void escapesDoubleQuotes() {
            assertThat(CloudJournalSerializer.jsonString("say \"hi\""))
                    .isEqualTo("\"say \\\"hi\\\"\"");
        }

        @Test
        @DisplayName("escapes backslash")
        void escapesBackslash() {
            assertThat(CloudJournalSerializer.jsonString("a\\b")).isEqualTo("\"a\\\\b\"");
        }

        @Test
        @DisplayName("escapes newline")
        void escapesNewline() {
            assertThat(CloudJournalSerializer.jsonString("a\nb")).isEqualTo("\"a\\nb\"");
        }
    }
}
