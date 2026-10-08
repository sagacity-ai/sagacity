package dev.sagacity.core.journal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * {@link AuditStore} that writes entries to {@link java.util.logging.Logger} at INFO level.
 *
 * <p>Requires zero infrastructure — no database, no configuration.
 * Intended for evaluating Sagacity and for local development where
 * a human-readable audit log in the application's log output is sufficient.
 *
 * <p>Also maintains an in-memory index so {@link #findBySagaId} works for
 * the duration of the JVM session. Entries are not persisted across restarts.
 *
 * <h2>Enable via auto-configuration</h2>
 * <pre>{@code
 * sagacity:
 *   audit:
 *     store: slf4j
 * }</pre>
 *
 * <h2>Log output format</h2>
 * <pre>
 * [SAGACITY AUDIT] sagaId=my-saga seq=1 tool=chargeCard phase=Intent input={"amount":100}
 * [SAGACITY AUDIT] sagaId=my-saga seq=2 tool=chargeCard phase=Executed result={"txId":"tx-1"}
 * </pre>
 */
public final class Slf4jAuditStore implements AuditStore {

    private static final Logger log = Logger.getLogger(Slf4jAuditStore.class.getName());

    /** In-memory index for findBySagaId — not durable, session-scoped only. */
    private final Map<String, List<AuditEntry>> index = new ConcurrentHashMap<>();

    @Override
    public AuditEntry append(String sagaId, String toolName, Phase phase, String input) {
        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<AuditEntry> saga = index.computeIfAbsent(sagaId, k -> new java.util.ArrayList<>());
        long seq = saga.size() + 1L;

        AuditEntry entry = new AuditEntry(sagaId, seq, toolName, phase, input, timestamp);
        saga.add(entry);

        log.info(String.format("[SAGACITY AUDIT] sagaId=%s seq=%d tool=%s phase=%s data=%s input=%s",
                sagaId, seq, toolName,
                phase.discriminator(),
                phase.toJson(),
                truncate(input, 200)));

        return entry;
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId) {
        return List.copyOf(index.getOrDefault(sagaId, List.of()));
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId, Class<? extends Phase> phaseType) {
        return findBySagaId(sagaId).stream()
                .filter(e -> phaseType.isInstance(e.phase()))
                .toList();
    }

    /** Truncates long inputs in log output to keep lines readable. */
    private static String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "…";
    }
}
