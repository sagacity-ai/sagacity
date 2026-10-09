package dev.sagacity.audit;

import java.time.Instant;
import java.util.List;

import dev.sagacity.audit.AuditEntry;
import dev.sagacity.audit.AuditStore;
import dev.sagacity.audit.HashChain;
import dev.sagacity.audit.InMemoryAuditStore;
import dev.sagacity.audit.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditExporterTest {

    @Test
    @DisplayName("exportJsonLines produces one line per entry")
    void exportJsonLinesProducesOneLinePerEntry() {
        AuditStore store = new InMemoryAuditStore();
        store.append("s1", "tool-a", new Phase.Intent(), "input1");
        store.append("s1", "tool-a", new Phase.Executed("result1"), "input1");
        store.append("s1", "tool-b", new Phase.Intent(), "input2");

        AuditExporter exporter = new AuditExporter(store);
        String export = exporter.exportJsonLines("s1");

        String[] lines = export.split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).contains("\"toolName\":\"tool-a\"");
        assertThat(lines[0]).contains("\"phase\":\"Intent\"");
        assertThat(lines[1]).contains("\"phase\":\"Executed\"");
        assertThat(lines[1]).contains("\"phaseData\":{\"result\":\"result1\"}");
        assertThat(lines[2]).contains("\"toolName\":\"tool-b\"");
    }

    @Test
    @DisplayName("exportJsonLines returns empty string for unknown saga")
    void exportJsonLinesEmptyForUnknownSaga() {
        AuditExporter exporter = new AuditExporter(new InMemoryAuditStore());
        assertThat(exporter.exportJsonLines("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("exportJsonLines escapes special characters")
    void exportJsonLinesEscapesSpecialCharacters() {
        AuditStore store = new InMemoryAuditStore();
        store.append("s1", "tool", new Phase.Intent(), "has \"quotes\" and \nnewlines");

        String export = new AuditExporter(store).exportJsonLines("s1");
        assertThat(export).contains("\\\"quotes\\\"");
        assertThat(export).contains("\\n");
        assertThat(export).doesNotContain("\n\n");
    }

    @Test
    @DisplayName("verify returns a result for journal entries")
    void verifyReturnsResult() {
        AuditStore store = new InMemoryAuditStore();
        store.append("s1", "tool", new Phase.Intent(), "in");

        AuditExporter.VerificationResult result = new AuditExporter(store).verify("s1");
        assertThat(result).isNotNull();
        assertThat(result.entryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("verify on empty journal is valid")
    void verifyEmptyJournalIsValid() {
        AuditExporter.VerificationResult result = new AuditExporter(new InMemoryAuditStore()).verify("empty");
        assertThat(result.valid()).isTrue();
        assertThat(result.entryCount()).isZero();
    }

    @Test
    @DisplayName("verify on unchained journal reports 'not hash-chained' rather than tampered")
    void verifyUnchainedJournalSaysSo() {
        AuditStore store = new InMemoryAuditStore();
        store.append("s1", "tool", new Phase.Intent(), "in");
        store.append("s1", "tool", new Phase.Executed("ok"), "in");

        AuditExporter.VerificationResult result = new AuditExporter(store).verify("s1");
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("not hash-chained");
        assertThat(result.breakAtIndex()).isEqualTo(-1);
        assertThat(result.entryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("verify on chained journal detects tampered entry")
    void verifyChainedJournalDetectsTamperedEntry() {
        Instant t1 = Instant.parse("2026-07-21T10:00:00Z");
        Instant t2 = Instant.parse("2026-07-21T10:00:01Z");
        String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                "s1", 1, "tool", new Phase.Intent(), "in", t1);
        String hash2 = HashChain.computeHash(hash1,
                "s1", 2, "tool", new Phase.Executed("ok"), "in", t2);

        // Tamper: change phase data of entry 2 but keep old hash
        AuditStore tampered = new FixedAuditStore(List.of(
                new AuditEntry("s1", 1, "tool", new Phase.Intent(), "in", t1, hash1),
                new AuditEntry("s1", 2, "tool", new Phase.Executed("TAMPERED"), "in", t2, hash2)));

        AuditExporter.VerificationResult result = new AuditExporter(tampered).verify("s1");
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("hash mismatch at seq=2");
        assertThat(result.breakAtIndex()).isEqualTo(1);
    }

    /** Read-only AuditStore backed by a fixed list — for tamper-detection tests. */
    private record FixedAuditStore(List<AuditEntry> entries) implements AuditStore {

        @Override
        public AuditEntry append(String sagaId, String toolName, Phase phase, String input) {
            throw new UnsupportedOperationException("read-only test store");
        }

        @Override
        public List<AuditEntry> findBySagaId(String sagaId) {
            return this.entries;
        }

        @Override
        public List<AuditEntry> findBySagaId(String sagaId, Class<? extends Phase> phaseType) {
            return this.entries.stream().filter(e -> phaseType.isInstance(e.phase())).toList();
        }
    }
}
