package dev.sagacity.core.journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled JSON serializer/deserializer for the Sagacity Cloud journal API.
 *
 * <h2>Why no Jackson/Gson?</h2>
 * <p>{@code sagacity-core} has zero runtime dependencies.
 *
 * <h2>Wire format</h2>
 * <ul>
 *   <li>Append request: {@code {"sagaId":...,"toolName":...,"phase":...,"phaseData":...,"input":...}}
 *   <li>Entry response: same plus {@code "seq"}, {@code "timestamp"} (ISO-8601), {@code "hash"}
 *   <li>Entries response: {@code {"entries":[...]}}
 * </ul>
 */
final class CloudJournalSerializer {

    // ── serialization ─────────────────────────────────────────────────────────

    String serializeAppendRequest(String sagaId, String toolName, Phase phase, String input) {
        return "{"
                + "\"sagaId\":"     + jsonString(sagaId)                 + ","
                + "\"toolName\":"   + jsonString(toolName)               + ","
                + "\"phase\":"      + jsonString(phase.discriminator())  + ","
                + "\"phaseData\":"  + phase.toJson()                     + ","
                + "\"input\":"      + jsonString(input)
                + "}";
    }

    // ── deserialization ───────────────────────────────────────────────────────

    AuditEntry deserializeEntry(String json) {
        return parseEntry(json);
    }

    List<AuditEntry> deserializeEntries(String json) {
        String arrayJson = extractArrayField(json, "entries");
        return parseArray(arrayJson);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

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

    private static AuditEntry parseEntry(String json) {
        String sagaId     = extractStringField(json, "sagaId");
        long seq          = extractLongField(json, "seq");
        String toolName   = extractStringField(json, "toolName");
        String discriminator = extractStringField(json, "phase");
        String phaseData  = extractRawField(json, "phaseData");
        Phase phase       = Phase.fromStorage(discriminator, phaseData);
        String input      = extractStringField(json, "input");
        Instant timestamp = Instant.parse(extractStringField(json, "timestamp"));
        String hash       = extractStringField(json, "hash");
        return new AuditEntry(sagaId, seq, toolName, phase, input, timestamp, hash);
    }

    private static List<AuditEntry> parseArray(String arrayJson) {
        List<AuditEntry> entries = new ArrayList<>();
        String trimmed = arrayJson.trim();
        if (trimmed.equals("[]") || trimmed.isEmpty()) return entries;
        trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        for (String obj : splitObjects(trimmed)) {
            entries.add(parseEntry(obj.trim()));
        }
        return entries;
    }

    private static List<String> splitObjects(String input) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int start = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    objects.add(input.substring(start, i + 1));
                    if (i + 1 < input.length() && input.charAt(i + 1) == ',') i++;
                    start = i + 1;
                }
            }
        }
        return objects;
    }

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
                        default -> sb.append(next);
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

        int end = valueStart;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(valueStart, end).trim();
    }

    /**
     * Extracts a raw JSON value (object or array) for fields like {@code phaseData}.
     * Returns "{}" if the field is absent.
     */
    private static String extractRawField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return "{}";

        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return "{}";

        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;
        if (valueStart >= json.length() || json.charAt(valueStart) != '{') return "{}";

        int depth = 0;
        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(valueStart, i + 1);
            }
        }
        return "{}";
    }

    private static String extractArrayField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return "[]";

        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return "[]";

        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;
        if (valueStart >= json.length() || json.charAt(valueStart) != '[') return "[]";

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
            throw new CloudJournalException("Cannot parse long field '" + field + "': " + value, ex);
        }
    }
}
