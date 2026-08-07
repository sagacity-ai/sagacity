package dev.sagacity.core.audit;

import java.time.Instant;
import java.util.List;

import dev.sagacity.core.journal.HashChain;
import dev.sagacity.core.journal.InMemorySideEffectJournal;
import dev.sagacity.core.journal.JournalEntry;
import dev.sagacity.core.journal.Phase;
import dev.sagacity.core.journal.SideEffectJournal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditExporterTest {

	@Test
	void exportJsonLines_producesOneLinePerEntry() {
		SideEffectJournal journal = new InMemorySideEffectJournal();
		journal.append("s1", "tool-a", Phase.INTENT, "input1", "");
		journal.append("s1", "tool-a", Phase.EXECUTED, "input1", "result1");
		journal.append("s1", "tool-b", Phase.INTENT, "input2", "");

		AuditExporter exporter = new AuditExporter(journal);
		String export = exporter.exportJsonLines("s1");

		String[] lines = export.split("\n");
		assertThat(lines).hasSize(3);
		assertThat(lines[0]).contains("\"toolName\":\"tool-a\"");
		assertThat(lines[0]).contains("\"phase\":\"INTENT\"");
		assertThat(lines[1]).contains("\"phase\":\"EXECUTED\"");
		assertThat(lines[2]).contains("\"toolName\":\"tool-b\"");
	}

	@Test
	void exportJsonLines_emptyForUnknownSaga() {
		SideEffectJournal journal = new InMemorySideEffectJournal();
		AuditExporter exporter = new AuditExporter(journal);

		assertThat(exporter.exportJsonLines("nonexistent")).isEmpty();
	}

	@Test
	void exportJsonLines_escapesSpecialCharacters() {
		SideEffectJournal journal = new InMemorySideEffectJournal();
		journal.append("s1", "tool", Phase.INTENT, "has \"quotes\" and \nnewlines", "");

		AuditExporter exporter = new AuditExporter(journal);
		String export = exporter.exportJsonLines("s1");

		assertThat(export).contains("\\\"quotes\\\""); // escaped quotes
		assertThat(export).contains("\\n"); // escaped newline
		assertThat(export).doesNotContain("\n\n"); // no raw newlines in mid-line
	}

	@Test
	void verify_returnsResultForJournalEntries() {
		SideEffectJournal journal = new InMemorySideEffectJournal();
		AuditExporter exporter = new AuditExporter(journal);
		journal.append("s1", "tool", Phase.INTENT, "in", "");

		AuditExporter.VerificationResult result = exporter.verify("s1");
		// InMemory uses empty hash (no chain), so verify will detect mismatch.
		// This validates the API contract.
		assertThat(result).isNotNull();
		assertThat(result.entryCount()).isEqualTo(1);
	}

	@Test
	void verify_emptyJournal_isValid() {
		SideEffectJournal journal = new InMemorySideEffectJournal();
		AuditExporter exporter = new AuditExporter(journal);

		AuditExporter.VerificationResult result = exporter.verify("empty");
		assertThat(result.valid()).isTrue();
		assertThat(result.entryCount()).isZero();
	}

	@Test
	void verify_unchainedJournal_saysSoInsteadOfReportingTampering() {
		SideEffectJournal journal = new InMemorySideEffectJournal();
		journal.append("s1", "tool", Phase.INTENT, "in", "");
		journal.append("s1", "tool", Phase.EXECUTED, "in", "ok");
		AuditExporter exporter = new AuditExporter(journal);

		AuditExporter.VerificationResult result = exporter.verify("s1");

		// Not valid — an unchained journal proves nothing — but an operator must
		// be able to tell "no evidence recorded" from "evidence says tampered".
		assertThat(result.valid()).isFalse();
		assertThat(result.message()).contains("not hash-chained");
		assertThat(result.breakAtIndex()).isEqualTo(-1);
		assertThat(result.entryCount()).isEqualTo(2);
	}

	@Test
	void verify_chainedJournal_detectsATamperedEntry() {
		Instant t1 = Instant.parse("2026-07-21T10:00:00Z");
		Instant t2 = Instant.parse("2026-07-21T10:00:01Z");
		String hash1 = HashChain.computeHash(HashChain.zeroHash(), "s1", 1, "tool",
				Phase.INTENT, "in", "", t1);
		String hash2 = HashChain.computeHash(hash1, "s1", 2, "tool", Phase.EXECUTED, "in", "ok", t2);

		SideEffectJournal tampered = new FixedJournal(List.of(
				new JournalEntry("s1", 1, "tool", Phase.INTENT, "in", "", t1, hash1),
				new JournalEntry("s1", 2, "tool", Phase.EXECUTED, "in", "ROLLED-BACK", t2, hash2)));

		AuditExporter.VerificationResult result = new AuditExporter(tampered).verify("s1");

		assertThat(result.valid()).isFalse();
		assertThat(result.message()).contains("hash mismatch at seq=2");
		assertThat(result.breakAtIndex()).isEqualTo(1);
	}

	/** Serves a fixed entry list so a tampered chain can be constructed directly. */
	private record FixedJournal(List<JournalEntry> entries) implements SideEffectJournal {

		@Override
		public JournalEntry append(String sagaId, String toolName, Phase phase, String input, String payload) {
			throw new UnsupportedOperationException("read-only test journal");
		}

		@Override
		public List<JournalEntry> entries(String sagaId) {
			return this.entries;
		}
	}

}
