package dev.sagacity.audit;

/**
 * Lifecycle phase of one side effect in the audit trail.
 *
 * <p>Each variant is a record carrying exactly the data relevant to that phase.
 * Using a sealed interface makes exhaustive switch possible at compile time —
 * the compiler guarantees every phase is handled, preventing silent gaps in
 * compensation logic or audit reporting.
 *
 * <p>Serialized to the journal as a two-column pair:
 * <ul>
 *   <li>{@code phase} — the simple class name (e.g. {@code "Executed"})
 *   <li>{@code phase_data} — JSON of the variant's fields, or {@code "{}"} if none
 * </ul>
 *
 * <h2>Usage with pattern matching</h2>
 * <pre>{@code
 * switch (entry.phase()) {
 *     case Phase.Executed e  -> compensate(e.result());
 *     case Phase.Failed f    -> log.warn("Failed: {}", f.error());
 *     case Phase.Rejected r  -> notify(r.reason());
 *     default                -> {}  // other phases carry no extra data
 * }
 * }</pre>
 */
public sealed interface Phase
        permits Phase.Intent, Phase.Executed, Phase.Failed,
                Phase.Compensated, Phase.CompensationFailed,
                Phase.AwaitingApproval, Phase.Approved, Phase.Rejected {

    /**
     * Journaled before the tool executes: "we are about to do this."
     * Input is captured at this point.
     */
    record Intent() implements Phase {}

    /**
     * The tool executed successfully.
     *
     * @param result snapshot of the tool's return value (JSON or plain text)
     */
    record Executed(String result) implements Phase {}

    /**
     * The tool threw an exception. Effect state may be unknown —
     * the tool may have partially executed.
     *
     * @param error the exception message or class name
     */
    record Failed(String error) implements Phase {}

    /**
     * The declared compensation for this effect ran successfully.
     */
    record Compensated() implements Phase {}

    /**
     * The declared compensation itself failed.
     *
     * @param error the compensation exception message
     */
    record CompensationFailed(String error) implements Phase {}

    /**
     * The saga is suspended waiting for human approval on an IRREVERSIBLE tool.
     */
    record AwaitingApproval() implements Phase {}

    /**
     * A human approved the execution of an IRREVERSIBLE tool.
     */
    record Approved() implements Phase {}

    /**
     * A human rejected the execution of an IRREVERSIBLE tool.
     *
     * @param reason the human-provided rejection reason, or empty string
     */
    record Rejected(String reason) implements Phase {}

    // ── Serialization helpers ──────────────────────────────────────────────

    /**
     * Returns the discriminator string stored in the {@code phase} column.
     * Uses the simple class name so it is stable and readable.
     */
    default String discriminator() {
        return this.getClass().getSimpleName();
    }

    /**
     * Deserializes a {@link Phase} from the stored discriminator and JSON data.
     *
     * @param discriminator value from the {@code phase} column
     * @param json          value from the {@code phase_data} column
     * @return the matching Phase variant
     * @throws IllegalArgumentException if the discriminator is unknown
     */
    static Phase fromStorage(String discriminator, String json) {
        return switch (discriminator) {
            case "Intent"            -> new Intent();
            case "Executed"          -> new Executed(extractField(json, "result"));
            case "Failed"            -> new Failed(extractField(json, "error"));
            case "Compensated"       -> new Compensated();
            case "CompensationFailed"-> new CompensationFailed(extractField(json, "error"));
            case "AwaitingApproval"  -> new AwaitingApproval();
            case "Approved"          -> new Approved();
            case "Rejected"          -> new Rejected(extractField(json, "reason"));
            default -> throw new IllegalArgumentException(
                    "Unknown Phase discriminator: " + discriminator);
        };
    }

    /**
     * Serializes this Phase's variant fields to a JSON object string.
     * Uses manual JSON construction — no Jackson dependency in sagacity-core.
     */
    default String toJson() {
        return switch (this) {
            case Intent i            -> "{}";
            case Executed e          -> jsonField("result", e.result());
            case Failed f            -> jsonField("error", f.error());
            case Compensated c       -> "{}";
            case CompensationFailed cf -> jsonField("error", cf.error());
            case AwaitingApproval a  -> "{}";
            case Approved ap         -> "{}";
            case Rejected r          -> jsonField("reason", r.reason());
        };
    }

    /** Builds a single-field JSON object: {@code {"key":"value"}}. */
    private static String jsonField(String key, String value) {
        if (value == null) value = "";
        // Escape backslash and double-quote to keep JSON valid.
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"" + key + "\":\"" + escaped + "\"}";
    }

    /**
     * Extracts a single string field from a minimal JSON object.
     * Only handles the simple {@code {"key":"value"}} shape this class emits.
     * Returns empty string if the field is absent or the JSON is malformed.
     */
    private static String extractField(String json, String key) {
        if (json == null || json.isBlank() || json.equals("{}")) return "";
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        // Find the closing quote, respecting backslash escapes.
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
