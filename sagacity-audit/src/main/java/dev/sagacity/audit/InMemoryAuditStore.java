package dev.sagacity.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link AuditStore} for development and testing.
 *
 * <p>Entries are stored in a {@link ConcurrentHashMap} and are lost when the
 * JVM exits. Does not maintain a hash chain. Use {@link JdbcAuditStore} for
 * any environment where durability or tamper evidence is required.
 */
public final class InMemoryAuditStore implements AuditStore {

    private final Map<String, List<AuditEntry>> store = new ConcurrentHashMap<>();

    private final AtomicLong globalSeq = new AtomicLong(0);

    @Override
    public AuditEntry append(String sagaId, String toolName, Phase phase, String input) {
        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long seq = store.computeIfAbsent(sagaId, k -> new ArrayList<>()).size() + 1L;

        AuditEntry entry = new AuditEntry(sagaId, seq, toolName, phase, input, timestamp);
        store.computeIfAbsent(sagaId, k -> new ArrayList<>()).add(entry);
        return entry;
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId) {
        return List.copyOf(store.getOrDefault(sagaId, List.of()));
    }

    @Override
    public List<AuditEntry> findBySagaId(String sagaId, Class<? extends Phase> phaseType) {
        return findBySagaId(sagaId).stream()
                .filter(e -> phaseType.isInstance(e.phase()))
                .toList();
    }
}
