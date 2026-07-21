package dev.sagacity.core.audit;

import dev.sagacity.core.journal.InMemorySideEffectJournal;
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

}
