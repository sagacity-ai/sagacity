package dev.sagacity.core.journal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

	// ── Canonical encoding: one entry must have exactly one preimage ────────

	@Test
	void computeHash_fieldContentCannotImpersonateAFieldBoundary() {
		Instant now = Instant.parse("2026-07-21T10:00:00Z");

		// Under a plain delimiter join these two entries collapse to the same
		// content string. They are different entries and must hash differently.
		String hash1 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
				Phase.EXECUTED, "a|b", "c", now);
		String hash2 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
				Phase.EXECUTED, "a", "b|c", now);

		assertThat(hash1).isNotEqualTo(hash2);
	}

	@Test
	void computeHash_shiftingCharactersBetweenAdjacentFieldsChangesHash() {
		Instant now = Instant.parse("2026-07-21T10:00:00Z");

		String hash1 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "transferFunds",
				Phase.EXECUTED, "", "", now);
		String hash2 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "transfer",
				Phase.EXECUTED, "Funds", "", now);

		assertThat(hash1).isNotEqualTo(hash2);
	}

	@Test
	void computeHash_handlesMultiByteCharactersUnambiguously() {
		Instant now = Instant.parse("2026-07-21T10:00:00Z");

		String hash1 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
				Phase.EXECUTED, "€uro", "", now);
		String hash2 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
				Phase.EXECUTED, "€", "uro", now);

		assertThat(hash1).isNotEqualTo(hash2);
	}

	// ── Timestamp precision must survive a microsecond-resolution store ─────

	@Test
	void computeHash_ignoresSubMicrosecondPrecision() {
		// Postgres TIMESTAMP holds microseconds. An Instant carrying nanoseconds
		// must hash to what will be read back, not to what was briefly in memory.
		Instant nanos = Instant.parse("2026-07-21T10:00:00Z").plusNanos(123_456_789);
		Instant micros = nanos.truncatedTo(ChronoUnit.MICROS);

		assertThat(nanos).isNotEqualTo(micros);
		assertThat(HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
				Phase.INTENT, "{}", "", nanos))
				.isEqualTo(HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
						Phase.INTENT, "{}", "", micros));
	}

	@Test
	void computeHash_stillDistinguishesTimestampsOneMicrosecondApart() {
		Instant t1 = Instant.parse("2026-07-21T10:00:00Z");
		Instant t2 = t1.plusNanos(1_000);

		assertThat(HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
				Phase.INTENT, "{}", "", t1))
				.isNotEqualTo(HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
						Phase.INTENT, "{}", "", t2));
	}

	@Test
	void canonicalTimestamp_isFixedWidthRegardlessOfTrailingZeros() {
		// Instant.toString() suppresses trailing zeros, so its width varies.
		// The canonical form must not.
		String whole = HashChain.canonicalTimestamp(Instant.parse("2026-07-21T10:00:00Z"));
		String fractional = HashChain.canonicalTimestamp(
				Instant.parse("2026-07-21T10:00:00Z").plusNanos(123_000));

		assertThat(whole).isEqualTo("1784628000.000000");
		assertThat(fractional).isEqualTo("1784628000.000123");
		assertThat(whole).hasSameSizeAs(fractional);
	}
}
