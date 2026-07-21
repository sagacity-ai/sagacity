package dev.sagacity.core.journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory journal for tests and demos. Not durable — the Postgres
 * implementation arrives in M1.
 */
public final class InMemorySideEffectJournal implements SideEffectJournal {

	private final Map<String, List<JournalEntry>> entriesBySaga = new ConcurrentHashMap<>();

	private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

	@Override
	public JournalEntry append(String sagaId, String toolName, Phase phase, String input, String payload) {
		long seq = this.sequences.computeIfAbsent(sagaId, id -> new AtomicLong()).incrementAndGet();
		JournalEntry entry = new JournalEntry(sagaId, seq, toolName, phase, input, payload, Instant.now());
		this.entriesBySaga.computeIfAbsent(sagaId, id -> new ArrayList<>());
		synchronized (this.entriesBySaga.get(sagaId)) {
			this.entriesBySaga.get(sagaId).add(entry);
		}
		return entry;
	}

	@Override
	public List<JournalEntry> entries(String sagaId) {
		List<JournalEntry> entries = this.entriesBySaga.getOrDefault(sagaId, List.of());
		synchronized (entries) {
			return List.copyOf(entries);
		}
	}

}
