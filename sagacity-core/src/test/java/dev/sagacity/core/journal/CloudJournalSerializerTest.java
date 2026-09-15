package dev.sagacity.core.journal;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloudJournalSerializerTest {

	private final CloudJournalSerializer serializer = new CloudJournalSerializer();

	// ── serializeAppendRequest ─────────────────────────────────────────────────

	@Test
	void serializeAppendRequest_producesValidJson() {
		String json = serializer.serializeAppendRequest(
				"saga-1", "reserveInventory", Phase.INTENT, "{\"qty\":5}", "");

		assertThat(json).contains("\"sagaId\":\"saga-1\"");
		assertThat(json).contains("\"toolName\":\"reserveInventory\"");
		assertThat(json).contains("\"phase\":\"INTENT\"");
		assertThat(json).contains("\"input\":\"{\\\"qty\\\":5}\"");
		assertThat(json).contains("\"payload\":\"\"");
	}

	@Test
	void serializeAppendRequest_escapesSpecialCharactersInValues() {
		String json = serializer.serializeAppendRequest(
				"saga-1", "tool", Phase.EXECUTED, "line1\nline2", "result\"quoted\"");

		assertThat(json).contains("\"input\":\"line1\\nline2\"");
		assertThat(json).contains("\"payload\":\"result\\\"quoted\\\"\"");
	}

	@Test
	void serializeAppendRequest_handlesEmptyStrings() {
		String json = serializer.serializeAppendRequest("s", "t", Phase.FAILED, "", "");

		assertThat(json).contains("\"input\":\"\"");
		assertThat(json).contains("\"payload\":\"\"");
	}

	// ── jsonString ─────────────────────────────────────────────────────────────

	@Test
	void jsonString_wrapsInQuotes() {
		assertThat(CloudJournalSerializer.jsonString("hello")).isEqualTo("\"hello\"");
	}

	@Test
	void jsonString_escapesQuote() {
		assertThat(CloudJournalSerializer.jsonString("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
	}

	@Test
	void jsonString_escapesBackslash() {
		assertThat(CloudJournalSerializer.jsonString("a\\b")).isEqualTo("\"a\\\\b\"");
	}

	@Test
	void jsonString_escapesNewline() {
		assertThat(CloudJournalSerializer.jsonString("a\nb")).isEqualTo("\"a\\nb\"");
	}

	@Test
	void jsonString_escapesTab() {
		assertThat(CloudJournalSerializer.jsonString("a\tb")).isEqualTo("\"a\\tb\"");
	}

	@Test
	void jsonString_escapesControlCharacter() {
		// U+0001 must be escaped as \u0001
		assertThat(CloudJournalSerializer.jsonString("\u0001")).isEqualTo("\"\\u0001\"");
	}

	@Test
	void jsonString_nullReturnsNullLiteral() {
		assertThat(CloudJournalSerializer.jsonString(null)).isEqualTo("null");
	}

	// ── deserializeEntry ──────────────────────────────────────────────────────

	@Test
	void deserializeEntry_parsesAllFields() {
		String json = """
				{
				  "sagaId": "saga-1",
				  "seq": 3,
				  "toolName": "chargeCard",
				  "phase": "EXECUTED",
				  "input": "{\\"amount\\":100}",
				  "payload": "charge-xyz",
				  "timestamp": "2026-09-15T10:00:00Z",
				  "hash": "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"
				}
				""";

		JournalEntry entry = serializer.deserializeEntry(json);

		assertThat(entry.sagaId()).isEqualTo("saga-1");
		assertThat(entry.seq()).isEqualTo(3);
		assertThat(entry.toolName()).isEqualTo("chargeCard");
		assertThat(entry.phase()).isEqualTo(Phase.EXECUTED);
		assertThat(entry.input()).isEqualTo("{\"amount\":100}");
		assertThat(entry.payload()).isEqualTo("charge-xyz");
		assertThat(entry.timestamp()).isEqualTo(Instant.parse("2026-09-15T10:00:00Z"));
		assertThat(entry.hash()).isEqualTo("abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234");
	}

	@Test
	void deserializeEntry_handlesEscapedStringValues() {
		String json = """
				{"sagaId":"s","seq":1,"toolName":"t","phase":"INTENT",
				 "input":"line1\\nline2","payload":"","timestamp":"2026-01-01T00:00:00Z","hash":"a"}
				""";

		JournalEntry entry = serializer.deserializeEntry(json);
		assertThat(entry.input()).isEqualTo("line1\nline2");
	}

	// ── deserializeEntries ────────────────────────────────────────────────────

	@Test
	void deserializeEntries_parsesMultipleEntries() {
		String entry1 = entryJson("saga-1", 1, "tool", "INTENT", "", "");
		String entry2 = entryJson("saga-1", 2, "tool", "EXECUTED", "", "result");
		String json = "{\"entries\":[" + entry1 + "," + entry2 + "]}";

		List<JournalEntry> entries = serializer.deserializeEntries(json);

		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).seq()).isEqualTo(1);
		assertThat(entries.get(0).phase()).isEqualTo(Phase.INTENT);
		assertThat(entries.get(1).seq()).isEqualTo(2);
		assertThat(entries.get(1).phase()).isEqualTo(Phase.EXECUTED);
		assertThat(entries.get(1).payload()).isEqualTo("result");
	}

	@Test
	void deserializeEntries_emptyArrayReturnsEmptyList() {
		List<JournalEntry> entries = serializer.deserializeEntries("{\"entries\":[]}");
		assertThat(entries).isEmpty();
	}

	@Test
	void deserializeEntries_singleEntry() {
		String json = "{\"entries\":[" + entryJson("s", 1, "t", "FAILED", "in", "err") + "]}";

		List<JournalEntry> entries = serializer.deserializeEntries(json);
		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).phase()).isEqualTo(Phase.FAILED);
	}

	// ── extractStringField ────────────────────────────────────────────────────

	@Test
	void extractStringField_findsValue() {
		assertThat(CloudJournalSerializer.extractStringField("{\"key\":\"value\"}", "key"))
				.isEqualTo("value");
	}

	@Test
	void extractStringField_returnsEmptyWhenAbsent() {
		assertThat(CloudJournalSerializer.extractStringField("{\"other\":\"x\"}", "key"))
				.isEmpty();
	}

	@Test
	void extractStringField_handlesUnicodeEscape() {
		assertThat(CloudJournalSerializer.extractStringField("{\"key\":\"\\u0041\"}", "key"))
				.isEqualTo("A");
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private static String entryJson(String sagaId, long seq, String toolName,
			String phase, String input, String payload) {
		return String.format(
				"{\"sagaId\":\"%s\",\"seq\":%d,\"toolName\":\"%s\",\"phase\":\"%s\"," +
				"\"input\":\"%s\",\"payload\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"hash\":\"x\"}",
				sagaId, seq, toolName, phase, input, payload);
	}
}
