package dev.sagacity.core.journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled JSON serializer/deserializer for the Sagacity Cloud journal API.
 *
 * <h2>Why no Jackson/Gson?</h2>
 * <p>{@code sagacity-core} has zero runtime dependencies. Bringing in a JSON
 * library would add a transitive dependency to every user who only wants
 * compensation and journaling — including those not on Spring Boot. The wire
 * format for journal entries is small and stable, so hand-rolled parsing is
 * proportionate.
 *
 * <h2>Format contract</h2>
 * <ul>
 *   <li>Append request:  {@code {"sagaId":...,"toolName":...,"phase":...,"input":...,"payload":...}}
 *   <li>Entry response:  same fields plus {@code "seq"}, {@code "timestamp"} (ISO-8601), {@code "hash"}
 *   <li>Entries response: {@code {"entries":[...]}}
 * </ul>
 *
 * <p>Values are JSON-string-escaped before embedding. The parser is lenient on
 * whitespace but strict on field names.
 */
final class CloudJournalSerializer {

	// ── serialization ─────────────────────────────────────────────────────────

	String serializeAppendRequest(String sagaId, String toolName, Phase phase,
			String input, String payload) {
		return "{" +
				"\"sagaId\":" + jsonString(sagaId) + "," +
				"\"toolName\":" + jsonString(toolName) + "," +
				"\"phase\":" + jsonString(phase.name()) + "," +
				"\"input\":" + jsonString(input) + "," +
				"\"payload\":" + jsonString(payload) +
				"}";
	}

	// ── deserialization ───────────────────────────────────────────────────────

	JournalEntry deserializeEntry(String json) {
		return parseEntry(json);
	}

	List<JournalEntry> deserializeEntries(String json) {
		// Expect: {"entries":[...]}
		String arrayJson = extractArrayField(json, "entries");
		return parseArray(arrayJson);
	}

	// ── JSON helpers ──────────────────────────────────────────────────────────

	/**
	 * Wraps a string in JSON quotes and escapes special characters.
	 * Handles the characters that must be escaped per RFC 8259:
	 * quotation mark, reverse solidus, and control characters U+0000–U+001F.
	 */
	static String jsonString(String value) {
		if (value == null) return "null";
		StringBuilder sb = new StringBuilder(value.length() + 2);
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"'  -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
		return sb.toString();
	}

	// ── parsing ───────────────────────────────────────────────────────────────

	private static JournalEntry parseEntry(String json) {
		String sagaId    = extractStringField(json, "sagaId");
		long seq         = extractLongField(json, "seq");
		String toolName  = extractStringField(json, "toolName");
		Phase phase      = Phase.valueOf(extractStringField(json, "phase"));
		String input     = extractStringField(json, "input");
		String payload   = extractStringField(json, "payload");
		Instant timestamp = Instant.parse(extractStringField(json, "timestamp"));
		String hash      = extractStringField(json, "hash");
		return new JournalEntry(sagaId, seq, toolName, phase, input, payload, timestamp, hash);
	}

	private static List<JournalEntry> parseArray(String arrayJson) {
		List<JournalEntry> entries = new ArrayList<>();
		// Strip outer brackets and split on top-level object boundaries
		String trimmed = arrayJson.trim();
		if (trimmed.equals("[]") || trimmed.isEmpty()) {
			return entries;
		}
		// Remove leading [ and trailing ]
		trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
		// Split into individual objects by tracking brace depth
		List<String> objects = splitObjects(trimmed);
		for (String obj : objects) {
			entries.add(parseEntry(obj.trim()));
		}
		return entries;
	}

	/**
	 * Splits a comma-separated sequence of JSON objects at the top level.
	 * Handles nested objects and strings containing commas.
	 */
	private static List<String> splitObjects(String input) {
		List<String> objects = new ArrayList<>();
		int depth = 0;
		boolean inString = false;
		boolean escape = false;
		int start = 0;

		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (escape) {
				escape = false;
				continue;
			}
			if (c == '\\' && inString) {
				escape = true;
				continue;
			}
			if (c == '"') {
				inString = !inString;
				continue;
			}
			if (inString) continue;

			if (c == '{') depth++;
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					objects.add(input.substring(start, i + 1));
					// Skip the comma separator
					if (i + 1 < input.length() && input.charAt(i + 1) == ',') {
						i++;
					}
					start = i + 1;
				}
			}
		}
		return objects;
	}

	/**
	 * Extracts the string value of a JSON field from a flat (non-nested) JSON object.
	 * Returns empty string if the field is absent or null.
	 */
	static String extractStringField(String json, String field) {
		String key = "\"" + field + "\"";
		int keyIdx = json.indexOf(key);
		if (keyIdx < 0) return "";

		int colonIdx = json.indexOf(':', keyIdx + key.length());
		if (colonIdx < 0) return "";

		int valueStart = colonIdx + 1;
		while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;

		if (valueStart >= json.length()) return "";

		if (json.charAt(valueStart) == '"') {
			// String value — find closing quote, respecting escapes
			StringBuilder sb = new StringBuilder();
			int i = valueStart + 1;
			while (i < json.length()) {
				char c = json.charAt(i);
				if (c == '\\' && i + 1 < json.length()) {
					char next = json.charAt(i + 1);
					switch (next) {
						case '"'  -> sb.append('"');
						case '\\' -> sb.append('\\');
						case 'b'  -> sb.append('\b');
						case 'f'  -> sb.append('\f');
						case 'n'  -> sb.append('\n');
						case 'r'  -> sb.append('\r');
						case 't'  -> sb.append('\t');
						case 'u'  -> {
							if (i + 5 < json.length()) {
								sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
								i += 4;
							}
						}
						default   -> sb.append(next);
					}
					i += 2;
				} else if (c == '"') {
					break;
				} else {
					sb.append(c);
					i++;
				}
			}
			return sb.toString();
		}

		if (json.startsWith("null", valueStart)) return "";

		// Non-string value — read until comma, }, or end
		int end = valueStart;
		while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
		return json.substring(valueStart, end).trim();
	}

	/** Extracts the array value of a JSON field. Returns "[]" if absent. */
	private static String extractArrayField(String json, String field) {
		String key = "\"" + field + "\"";
		int keyIdx = json.indexOf(key);
		if (keyIdx < 0) return "[]";

		int colonIdx = json.indexOf(':', keyIdx + key.length());
		if (colonIdx < 0) return "[]";

		int valueStart = colonIdx + 1;
		while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;

		if (valueStart >= json.length() || json.charAt(valueStart) != '[') return "[]";

		// Find the matching closing bracket
		int depth = 0;
		for (int i = valueStart; i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '[') depth++;
			else if (c == ']') {
				depth--;
				if (depth == 0) return json.substring(valueStart, i + 1);
			}
		}
		return "[]";
	}

	private static long extractLongField(String json, String field) {
		String value = extractStringField(json, field);
		if (value.isEmpty()) return 0L;
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ex) {
			throw new CloudJournalException("Cannot parse long field '" + field + "' from value: " + value, ex);
		}
	}
}
