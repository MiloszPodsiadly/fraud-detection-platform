package com.frauddetection.common.events.merchant;

import com.frauddetection.common.events.merchant.MerchantProfile.Provenance;
import com.frauddetection.common.events.merchant.MerchantProfile.Reason;
import com.frauddetection.common.events.merchant.MerchantProfile.Status;
import com.frauddetection.common.events.merchant.MerchantProfile.WindowCount;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantProfileContractTest {
    private static final Instant TIME = Instant.parse("2027-01-15T10:00:00Z");
    private static final ScopeClaim SCOPE = new ScopeClaim(token('a'), token('b'), token('c'), token('d'), token('e'), 1);
    private static final OccurrenceClaim OCCURRENCE = new OccurrenceClaim(token('f'), token('1'), token('2'), 10, TIME, token('3'));

    @Test
    void immutableValueCarriesExactBindingAndSeparateWindowCountsWithTypedFactDispositions() {
        MerchantProfile profile = profile(provenance(), available(TIME.minusSeconds(3600), TIME, 2),
                available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 5));
        assertThat(profile.scope()).isEqualTo(SCOPE);
        assertThat(profile.occurrence()).isEqualTo(OCCURRENCE);
        assertThat(profile.recent().count()).isEqualTo(2);
        assertThat(profile.baseline().count()).isEqualTo(5);
        assertThat(profile.volume().status()).isEqualTo(MerchantProfileFacts.Status.UNTRUSTED);
        assertThat(profile.volume().reason()).isEqualTo(MerchantProfileFacts.Reason.CLIENT_ASSERTION);
        assertThat(profile.distinctCustomers().status()).isEqualTo(MerchantProfileFacts.Status.UNTRUSTED);
        assertThat(profile.category().status()).isEqualTo(MerchantProfileFacts.Status.UNSUPPORTED);
        assertThat(profile.labels().status()).isEqualTo(MerchantProfileFacts.Status.UNSUPPORTED);
        assertThat(profile.populationDenominator().status()).isEqualTo(MerchantProfileFacts.Status.MISSING);
        assertThat(profile.facts().institutionMerchantMapping().status()).isEqualTo(MerchantProfileFacts.Status.MISSING);
        assertThat(profile.computationVersion()).isEqualTo(MerchantProfile.COMPUTATION_VERSION);
    }

    @Test
    void unavailableNeverContainsAZeroCountAndAvailableZeroRequiresCoverage() {
        WindowCount unknown = new WindowCount(TIME.minusSeconds(3600), TIME, Status.UNAVAILABLE, null, Reason.HISTORY_MISSING);
        MerchantProfile unavailable = profile(new Provenance(TIME, null, null, null, null, null), unknown,
                new WindowCount(TIME.minusSeconds(7200), TIME.minusSeconds(3600), Status.UNAVAILABLE, null, Reason.MISSING_COVERAGE));
        assertThat(unavailable.recent().count()).isNull();
        assertThat(unavailable.recent()).isNotEqualTo(available(TIME.minusSeconds(3600), TIME, 0));
        assertThatThrownBy(() -> new WindowCount(TIME.minusSeconds(3600), TIME, Status.UNAVAILABLE, 0, Reason.HISTORY_MISSING))
                .hasMessage("MERCHANT_PROFILE_STATE");
        assertThatThrownBy(() -> new WindowCount(TIME.minusSeconds(3600), TIME, Status.AVAILABLE, null, Reason.NONE))
                .hasMessage("MERCHANT_PROFILE_STATE");
        assertThatThrownBy(() -> new WindowCount(TIME.minusSeconds(3600), TIME, Status.AVAILABLE, 0, Reason.HISTORY_MISSING))
                .hasMessage("MERCHANT_PROFILE_STATE");
        assertThatThrownBy(() -> new WindowCount(TIME.minusSeconds(3600), TIME, Status.UNAVAILABLE, null, Reason.NONE))
                .hasMessage("MERCHANT_PROFILE_STATE");
    }

    @Test
    void countWindowsMustBeAdjacentAndEndAtTheScoringOccurrenceWithinThirtyDays() {
        for (WindowCount recent : List.of(available(TIME.minusSeconds(3600), TIME.minusSeconds(1), 0),
                available(TIME.minusSeconds(3600), TIME.plusSeconds(1), 0))) {
            assertThatThrownBy(() -> profile(provenance(), recent, available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 0)))
                    .hasMessage("MERCHANT_PROFILE_WINDOW_BINDING");
        }
        assertThatThrownBy(() -> profile(provenance(), available(TIME.minusSeconds(3600), TIME, 0),
                available(TIME.minusSeconds(7200), TIME.minusSeconds(3601), 0))).hasMessage("MERCHANT_PROFILE_WINDOW_BINDING");
        assertThatThrownBy(() -> profile(provenance(), available(TIME.minusSeconds(20 * 86400L), TIME, 0),
                available(TIME.minusSeconds(31 * 86400L), TIME.minusSeconds(20 * 86400L), 0)))
                .hasMessage("MERCHANT_PROFILE_WINDOW_BINDING");
    }

    @Test
    void informativeValuesRequireWatermarkSequenceRetentionAndHistoryProvenance() {
        for (Provenance invalid : List.of(
                new Provenance(TIME, null, null, null, null, null),
                new Provenance(TIME, TIME.minusSeconds(7200), TIME.minusSeconds(7200), TIME.minusSeconds(1), 10L, token('4')),
                new Provenance(TIME, TIME.minusSeconds(7200), TIME.minusSeconds(7200), TIME, 9L, token('4')),
                new Provenance(TIME, TIME.minusSeconds(7200), TIME.minusSeconds(7200), TIME, 10L, null))) {
            assertThatThrownBy(() -> profile(invalid, available(TIME.minusSeconds(3600), TIME, 0),
                    available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 0))).hasMessage("MERCHANT_PROFILE_PROVENANCE");
        }
        for (Provenance invalid : List.of(
                new Provenance(TIME, TIME.minusSeconds(7199), TIME.minusSeconds(7200), TIME, 10L, token('4')),
                new Provenance(TIME, TIME.minusSeconds(7200), TIME.minusSeconds(7199), TIME, 10L, token('4')))) {
            assertThatThrownBy(() -> profile(invalid, available(TIME.minusSeconds(3600), TIME, 0),
                    available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 0))).hasMessage("MERCHANT_PROFILE_COVERAGE");
        }
    }

    @Test
    void malformedStatesTimesVersionsAndUnboundedCountsAreRejected() {
        for (int count : new int[] {-1, MerchantProfile.MAX_HISTORY_ROWS + 1}) {
            assertThatThrownBy(() -> available(TIME.minusSeconds(1), TIME, count)).hasMessage("MERCHANT_PROFILE_STATE");
        }
        assertThatThrownBy(() -> new WindowCount(TIME, TIME, Status.AVAILABLE, 0, Reason.NONE)).hasMessage("MERCHANT_PROFILE_WINDOW");
        assertThatThrownBy(() -> available(TIME.minusSeconds(1), TIME.plusNanos(1), 0)).hasMessage("MERCHANT_PROFILE_TIME");
        assertThatThrownBy(() -> new WindowCount(TIME.minusSeconds(1), TIME, Status.UNSUPPORTED, null, Reason.NONE))
                .hasMessage("MERCHANT_PROFILE_STATE");
        assertThatThrownBy(() -> new Provenance(TIME, null, TIME.minusSeconds(1), null, null, null)).hasMessage("MERCHANT_PROFILE_PROVENANCE");
        assertThatThrownBy(() -> new Provenance(TIME, null, null, null, null, "raw-history")).hasMessage("MERCHANT_PROFILE_HISTORY_COMMITMENT");
        MerchantProfile valid = profile(provenance(), available(TIME.minusSeconds(3600), TIME, 0),
                available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 0));
        for (int version : new int[] {0, 3}) {
            assertThatThrownBy(() -> new MerchantProfile(version, 1, SCOPE, OCCURRENCE, provenance(), valid.recent(), valid.baseline()))
                    .hasMessage("MERCHANT_PROFILE_VERSION");
            assertThatThrownBy(() -> new MerchantProfile(MerchantProfile.VERSION, version, SCOPE, OCCURRENCE, provenance(), valid.recent(), valid.baseline()))
                    .hasMessage("MERCHANT_PROFILE_VERSION");
        }
        assertThatThrownBy(() -> profile(new Provenance(TIME.minusSeconds(1), null, null, null, null, null),
                valid.recent(), valid.baseline())).hasMessage("MERCHANT_PROFILE_EVALUATION_TIME");
        assertThatThrownBy(() -> profile(provenance(), available(TIME.minusSeconds(3600), TIME, 25_000),
                available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 1))).hasMessage("MERCHANT_PROFILE_COUNT_LIMIT");
    }

    @Test
    void diagnosticsAndDefaultStringOutputContainNoIdentityOrPayloadReferences() {
        MerchantProfile profile = profile(provenance(), available(TIME.minusSeconds(3600), TIME, 0),
                available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 0));
        String diagnostic = profile.diagnostics().toString();
        for (char reference : "abcdef1234".toCharArray()) {
            assertThat(diagnostic).doesNotContain(token(reference));
            assertThat(profile.toString()).doesNotContain(token(reference));
            assertThat(profile.provenance().toString()).doesNotContain(token(reference));
        }
        assertThat(diagnostic).contains("AVAILABLE", "count=0", "UNSUPPORTED", "TAXONOMY_NOT_VERIFIED");
    }

    @Test
    void canonicalOutputIsBoundedReproducibleAndDefensive() {
        MerchantProfile profile = profile(provenance(), available(TIME.minusSeconds(3600), TIME, 25_000),
                available(TIME.minusSeconds(7200), TIME.minusSeconds(3600), 0));
        byte[] original = profile.canonicalBytes();
        assertThat(original.length).isLessThanOrEqualTo(MerchantProfile.MAX_CANONICAL_BYTES);
        assertThat(profile.canonicalBytes()).isEqualTo(original);
        byte[] mutableCopy = profile.canonicalBytes();
        mutableCopy[0] ^= 1;
        assertThat(profile.canonicalBytes()).isEqualTo(original);
        MerchantProfile changed = profile(provenance(), available(TIME.minusSeconds(3600), TIME, 24_999), profile.baseline());
        assertThat(changed.canonicalBytes()).isNotEqualTo(original);
    }

    private static MerchantProfile profile(Provenance provenance, WindowCount recent, WindowCount baseline) {
        return new MerchantProfile(MerchantProfile.VERSION, MerchantProfile.COMPUTATION_VERSION,
                SCOPE, OCCURRENCE, provenance, recent, baseline);
    }
    private static Provenance provenance() {
        return new Provenance(TIME, TIME.minusSeconds(7200), TIME.minusSeconds(7200), TIME, 10L, token('4'));
    }
    private static WindowCount available(Instant from, Instant to, int count) {
        return new WindowCount(from, to, Status.AVAILABLE, count, Reason.NONE);
    }
    private static String token(char value) { return String.valueOf(value).repeat(64); }
}
