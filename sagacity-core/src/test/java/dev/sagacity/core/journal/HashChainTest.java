package dev.sagacity.core.journal;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashChainTest {

	@Test
	void computeHash_isDeterministic() {
		String hash1 = HashChain.computeHash(HashChain.zeroHash(), "saga-1", 1, "tool",
				Phase.INTENT, "input", "payload", Instant.parse("2026-07-21T10:00:00Z"));
		String hash2 = HashChain.computeHash(HashChain.zeroHash(), "saga-1", 1, "tool",
				Phase.INTENT, "input", "payload", Instant.parse("2026-07-21T10:00:00Z"));
		assertThat(hash1).isEqualTo(hash2);
		assertThat(hash1).hasSize(64); // SHA-256 hex = 64 chars
	}

	@Test
	void computeHash_changesWithDifferentInput() {
		Instant now = Instant.now();
		String hash1 = HashChain.computeHash(HashChain.zeroHash(), "saga-1", 1, "tool",
				Phase.INTENT, "input-A", "", now);
		String hash2 = HashChain.computeHash(HashChain.zeroHash(), "saga-1", 1, "tool",
				Phase.INTENT, "input-B", "", now);
		assertThat(hash1).isNotEqualTo(hash2);
	}

	@Test
	void computeHash_changesWithDifferentPreviousHash() {
		Instant now = Instant.now();
		String hash1 = HashChain.computeHash("aaa", "saga-1", 1, "tool",
				Phase.INTENT, "input", "", now);
		String hash2 = HashChain.computeHash("bbb", "saga-1", 1, "tool",
				Phase.INTENT, "input", "", now);
		assertThat(hash1).isNotEqualTo(hash2);
	}

	@Test
	void verify_validChain_returnsTrue() {
		Instant t1 = Instant.parse("2026-07-21T10:00:00Z");
		Instant t2 = Instant.parse("2026-07-21T10:00:01Z");

		String hash1 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "reserveInventory",
				Phase.INTENT, "{}", "", t1);
		String hash2 = HashChain.computeHash(hash1, "s1", 2, "reserveInventory",
				Phase.EXECUTED, "{}", "ok", t2);

		List<JournalEntry> entries = List.of(
				new JournalEntry("s1", 1, "reserveInventory", Phase.INTENT, "{}", "", t1, hash1),
				new JournalEntry("s1", 2, "reserveInventory", Phase.EXECUTED, "{}", "ok", t2, hash2)
		);

		assertThat(HashChain.verify(entries)).isTrue();
	}

	@Test
	void verify_tamperedEntry_returnsFalse() {
		Instant t1 = Instant.parse("2026-07-21T10:00:00Z");
		Instant t2 = Instant.parse("2026-07-21T10:00:01Z");

		String hash1 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "reserveInventory",
				Phase.INTENT, "{}", "", t1);
		String hash2 = HashChain.computeHash(hash1, "s1", 2, "reserveInventory",
				Phase.EXECUTED, "{}", "ok", t2);

		// Tamper: change payload of entry 2 but keep the old hash
		List<JournalEntry> entries = List.of(
				new JournalEntry("s1", 1, "reserveInventory", Phase.INTENT, "{}", "", t1, hash1),
				new JournalEntry("s1", 2, "reserveInventory", Phase.EXECUTED, "{}", "TAMPERED", t2, hash2)
		);

		assertThat(HashChain.verify(entries)).isFalse();
	}

	@Test
	void verify_emptyList_returnsTrue() {
		assertThat(HashChain.verify(List.of())).isTrue();
	}
}
