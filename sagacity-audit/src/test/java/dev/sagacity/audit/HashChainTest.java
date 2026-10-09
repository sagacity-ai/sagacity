package dev.sagacity.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashChainTest {

    @Nested
    @DisplayName("determinism and sensitivity")
    class DeterminismTests {

        @Test
        @DisplayName("same inputs produce the same hash")
        void computeHashIsDeterministic() {
            Instant t = Instant.parse("2026-07-21T10:00:00Z");
            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "saga-1", 1, "tool", new Phase.Intent(), "input", t);
            String hash2 = HashChain.computeHash(HashChain.zeroHash(),
                    "saga-1", 1, "tool", new Phase.Intent(), "input", t);

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64);
        }

        @Test
        @DisplayName("different input changes the hash")
        void hashChangesWithDifferentInput() {
            Instant t = Instant.now();
            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "saga-1", 1, "tool", new Phase.Intent(), "input-A", t);
            String hash2 = HashChain.computeHash(HashChain.zeroHash(),
                    "saga-1", 1, "tool", new Phase.Intent(), "input-B", t);

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("different previous hash changes the hash")
        void hashChangesWithDifferentPreviousHash() {
            Instant t = Instant.now();
            String hash1 = HashChain.computeHash("aaa",
                    "saga-1", 1, "tool", new Phase.Intent(), "input", t);
            String hash2 = HashChain.computeHash("bbb",
                    "saga-1", 1, "tool", new Phase.Intent(), "input", t);

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("different phase discriminators change the hash")
        void hashChangesWithDifferentPhase() {
            Instant t = Instant.now();
            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Intent(), "input", t);
            String hash2 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Executed("result"), "input", t);

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("phase data is included in the hash")
        void phaseDataAffectsHash() {
            Instant t = Instant.now();
            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Executed("result-A"), "input", t);
            String hash2 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Executed("result-B"), "input", t);

            assertThat(hash1).isNotEqualTo(hash2);
        }
    }

    @Nested
    @DisplayName("verify chain")
    class VerifyTests {

        @Test
        @DisplayName("valid chain returns true")
        void validChainReturnsTrue() {
            Instant t1 = Instant.parse("2026-07-21T10:00:00Z");
            Instant t2 = Instant.parse("2026-07-21T10:00:01Z");

            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Intent(), "{}", t1);
            String hash2 = HashChain.computeHash(hash1,
                    "s1", 2, "tool", new Phase.Executed("ok"), "{}", t2);

            List<AuditEntry> entries = List.of(
                    new AuditEntry("s1", 1, "tool", new Phase.Intent(), "{}", t1, hash1),
                    new AuditEntry("s1", 2, "tool", new Phase.Executed("ok"), "{}", t2, hash2));

            assertThat(HashChain.verify(entries)).isTrue();
        }

        @Test
        @DisplayName("tampered entry returns false")
        void tamperedEntryReturnsFalse() {
            Instant t1 = Instant.parse("2026-07-21T10:00:00Z");
            Instant t2 = Instant.parse("2026-07-21T10:00:01Z");

            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Intent(), "{}", t1);
            String hash2 = HashChain.computeHash(hash1,
                    "s1", 2, "tool", new Phase.Executed("ok"), "{}", t2);

            // Tamper: change phase data of entry 2 but keep old hash
            List<AuditEntry> entries = List.of(
                    new AuditEntry("s1", 1, "tool", new Phase.Intent(), "{}", t1, hash1),
                    new AuditEntry("s1", 2, "tool", new Phase.Executed("TAMPERED"), "{}", t2, hash2));

            assertThat(HashChain.verify(entries)).isFalse();
        }

        @Test
        @DisplayName("empty list returns true")
        void emptyListReturnsTrue() {
            assertThat(HashChain.verify(List.of())).isTrue();
        }
    }

    @Nested
    @DisplayName("canonical encoding — no collisions")
    class EncodingTests {

        @Test
        @DisplayName("field content cannot impersonate a field boundary")
        void fieldContentCannotImpersonateFieldBoundary() {
            Instant t = Instant.parse("2026-07-21T10:00:00Z");
            // Under a plain delimiter join these two calls collapse to the same content
            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "toolAB", new Phase.Executed("c"), "{}", t);
            String hash2 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Executed("ABc"), "{}", t);

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("shifting characters between adjacent fields changes hash")
        void shiftingCharactersChangesHash() {
            Instant t = Instant.parse("2026-07-21T10:00:00Z");
            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "transferFunds", new Phase.Executed(""), "{}", t);
            String hash2 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "transfer", new Phase.Executed("Funds"), "{}", t);

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("multi-byte characters are unambiguous")
        void multiBytCharactersAreUnambiguous() {
            Instant t = Instant.parse("2026-07-21T10:00:00Z");
            String hash1 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Executed("€uro"), "{}", t);
            String hash2 = HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Executed("€"), "uro", t);

            assertThat(hash1).isNotEqualTo(hash2);
        }
    }

    @Nested
    @DisplayName("timestamp precision")
    class TimestampTests {

        @Test
        @DisplayName("sub-microsecond precision is truncated before hashing")
        void subMicrosecondPrecisionIsTruncated() {
            Instant nanos = Instant.parse("2026-07-21T10:00:00Z").plusNanos(123_456_789);
            Instant micros = nanos.truncatedTo(ChronoUnit.MICROS);

            assertThat(nanos).isNotEqualTo(micros);
            assertThat(HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Intent(), "{}", nanos))
                .isEqualTo(HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Intent(), "{}", micros));
        }

        @Test
        @DisplayName("timestamps one microsecond apart still differ")
        void timestampsOneMicrosecondApartDiffer() {
            Instant t1 = Instant.parse("2026-07-21T10:00:00Z");
            Instant t2 = t1.plusNanos(1_000);

            assertThat(HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Intent(), "{}", t1))
                .isNotEqualTo(HashChain.computeHash(HashChain.zeroHash(),
                    "s1", 1, "tool", new Phase.Intent(), "{}", t2));
        }

        @Test
        @DisplayName("canonical timestamp is fixed-width regardless of trailing zeros")
        void canonicalTimestampIsFixedWidth() {
            String whole = HashChain.canonicalTimestamp(Instant.parse("2026-07-21T10:00:00Z"));
            String fractional = HashChain.canonicalTimestamp(
                    Instant.parse("2026-07-21T10:00:00Z").plusNanos(123_000));

            assertThat(whole).isEqualTo("1784628000.000000");
            assertThat(fractional).isEqualTo("1784628000.000123");
            assertThat(whole).hasSameSizeAs(fractional);
        }
    }
}
