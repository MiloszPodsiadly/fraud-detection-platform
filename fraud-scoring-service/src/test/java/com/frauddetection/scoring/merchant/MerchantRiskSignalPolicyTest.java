package com.frauddetection.scoring.merchant;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.intelligence.EngineIntelligenceReasonCodePolicy;
import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantProfile.Provenance;
import com.frauddetection.common.events.merchant.MerchantProfile.WindowCount;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Signal;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.SourceAssertion;
import com.frauddetection.scoring.merchant.MerchantRiskSignalPolicy.Coverage;
import com.frauddetection.scoring.merchant.MerchantRiskSignalPolicy.Evaluation;
import com.frauddetection.scoring.merchant.MerchantRiskSignalPolicy.SignalOutcome;
import com.frauddetection.scoring.merchant.MerchantRiskSignalPolicy.State;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.frauddetection.scoring.merchant.MerchantRiskSignalReasonCode.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantRiskSignalPolicyTest {
    private static final Instant TIME = Instant.parse("2027-01-15T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final ScopeClaim SCOPE = new ScopeClaim(token(1), token(2), token(3), token(4), token(5), 1);
    private static final OccurrenceClaim OCCURRENCE = new OccurrenceClaim(token(6), token(7), token(8), 10, TIME, token(9));
    private static final Provenance PROVENANCE = new Provenance(TIME.plusSeconds(60), TIME.minusSeconds(10800),
            TIME.minusSeconds(10800), TIME.plusSeconds(1), 10L, token(10));

    @ParameterizedTest
    @CsvSource({"0,0,true", "0,5,true", "1,0,false", "1,1,false", "100,1,false", "1,100,false", "25000,0,false", "0,25000,true"})
    void fiveSignalTruthTableOnlyBoundedNoveltyHasAnInformativePositiveOrNegative(int recent, int baseline, boolean absent) {
        Evaluation result = evaluate(profile(recent, baseline));
        assertOutcome(result, Signal.MERCHANT_FRAUD_RATE, State.UNSUPPORTED, null, MERCHANT_FRAUD_RATE_LABELS_UNSUPPORTED);
        assertOutcome(result, Signal.MERCHANT_ACTIVITY_SPIKE, State.UNSUPPORTED, null, MERCHANT_SPIKE_METHOD_NOT_VALIDATED);
        assertOutcome(result, Signal.MERCHANT_CATEGORY_CONTEXT, State.UNSUPPORTED, null, MERCHANT_CATEGORY_METHOD_NOT_VALIDATED);
        assertOutcome(result, Signal.MERCHANT_POPULATION_CONCENTRATION, State.UNAVAILABLE, null, MERCHANT_CONCENTRATION_POPULATION_UNAVAILABLE);
        assertOutcome(result, Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW, State.AVAILABLE, absent,
                absent ? MERCHANT_FIRST_OBSERVED_IN_WINDOW : MERCHANT_PREVIOUSLY_OBSERVED_IN_WINDOW);
        assertThat(outcome(result, Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW).coverage()).isEqualTo(Coverage.COMPLETE_RECENT_WINDOW);
        assertThat(outcome(result, Signal.MERCHANT_ACTIVITY_SPIKE).coverage()).isEqualTo(Coverage.COMPLETE_COMPARISON_WINDOWS);
        assertThat(result.score()).isNull();
        assertThat(result.riskInference()).isEqualTo(MerchantRiskSignalPolicy.RiskInference.NOT_CALIBRATED);
    }

    @Test
    void olderBaselineActivityDoesNotChangeTheMeaningOfFirstObservedWithinTheRecentWindow() {
        Evaluation result = evaluate(profile(0, 10));
        assertThat(outcome(result, Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW).observed()).isTrue();
        assertThat(result.evidence().recent().start()).isEqualTo(TIME.minus(WINDOW));
        assertThat(result.evidence().recent().end()).isEqualTo(TIME);
        assertThat(result.evidence().baseline().count()).isEqualTo(10);
        assertThat(result.observationScope()).isEqualTo(MerchantRiskSignalPolicy.ObservationScope.INSTITUTION_MERCHANT_SOURCE_IDENTITY_VERSION);
        assertThat(result.signals().toString()).doesNotContain("FIRST_EVER", "CUSTOMER", "HIGH_RISK", "LOW_RISK");
    }

    @Test
    void apparentlyLargeSpikesTinySamplesAndZeroDenominatorsCannotInventAMethodologyOrFraudRate() {
        for (int[] counts : new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {24_999, 1}, {1, 24_999}}) {
            Evaluation result = evaluate(profile(counts[0], counts[1]));
            assertOutcome(result, Signal.MERCHANT_ACTIVITY_SPIKE, State.UNSUPPORTED, null, MERCHANT_SPIKE_METHOD_NOT_VALIDATED);
            assertOutcome(result, Signal.MERCHANT_FRAUD_RATE, State.UNSUPPORTED, null, MERCHANT_FRAUD_RATE_LABELS_UNSUPPORTED);
            assertOutcome(result, Signal.MERCHANT_POPULATION_CONCENTRATION, State.UNAVAILABLE, null, MERCHANT_CONCENTRATION_POPULATION_UNAVAILABLE);
            assertThat(result.score()).isNull();
        }
    }

    @Test
    void aWellFormedAvailableProfileAndMatchingTokensAreNotAdmission() {
        MerchantProfile profile = profile(0, 0);
        allBlocked(new MerchantRiskSignalPolicy(WINDOW, WINDOW, null).evaluate(SCOPE, OCCURRENCE, profile), MERCHANT_NOT_ADMITTED);
        MerchantRiskSignalPolicy policy = policy(profile);
        allBlocked(policy.evaluate(null, OCCURRENCE, profile), MERCHANT_NOT_ADMITTED);
        allBlocked(policy.evaluate(SCOPE, null, profile), MERCHANT_NOT_ADMITTED);
        allBlocked(policy.evaluate(SCOPE, OCCURRENCE, profile(1, 0)), MERCHANT_NOT_ADMITTED);
        allBlocked(policy.evaluate(SCOPE, OCCURRENCE, null), MERCHANT_NO_PROFILE);
    }

    @Test
    void admissionFailuresCannotLeakIssuerMessagesOrBecomeANegativeRiskFinding() {
        String sensitive = "raw-merchant-bank-secret";
        var policy = new MerchantRiskSignalPolicy(WINDOW, WINDOW, (scope, occurrence, profile) -> {
            throw new IllegalStateException(sensitive, new RuntimeException(token(55)));
        });
        Evaluation result = policy.evaluate(SCOPE, OCCURRENCE, profile(0, 0));
        allBlocked(result, MERCHANT_NOT_ADMITTED);
        assertThat(result.toString()).doesNotContain(sensitive, token(55));
    }

    @Test
    void equalMerchantTokensCannotJoinOtherIssuersInstitutionsSourcesEpochsOrIdentityVersions() {
        MerchantProfile profile = profile(0, 0);
        for (ScopeClaim expected : List.of(
                new ScopeClaim(token(99), SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(), 1),
                new ScopeClaim(SCOPE.issuerRef(), token(99), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(), 1),
                new ScopeClaim(SCOPE.issuerRef(), SCOPE.institutionRef(), token(99), SCOPE.sourceEpochRef(), SCOPE.merchantRef(), 1),
                new ScopeClaim(SCOPE.issuerRef(), SCOPE.institutionRef(), SCOPE.sourceRef(), token(99), SCOPE.merchantRef(), 1),
                new ScopeClaim(SCOPE.issuerRef(), SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), token(99), 1),
                new ScopeClaim(SCOPE.issuerRef(), SCOPE.institutionRef(), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(), 2))) {
            // Even an explicitly assumed test authorization cannot bypass the policy's independent binding comparison.
            var policy = new MerchantRiskSignalPolicy(WINDOW, WINDOW, new TestOnlyProfileAdmission(Set.of(new Binding(expected, OCCURRENCE, profile))));
            allBlocked(policy.evaluate(expected, OCCURRENCE, profile), MERCHANT_SCOPE_MISMATCH);
        }
    }

    @Test
    void eventTransactionScoringOccurrenceSequenceCutoffAndPayloadMustAllMatch() {
        MerchantProfile profile = profile(0, 0);
        for (OccurrenceClaim expected : List.of(
                new OccurrenceClaim(token(99), token(7), token(8), 10, TIME, token(9)),
                new OccurrenceClaim(token(6), token(99), token(8), 10, TIME, token(9)),
                new OccurrenceClaim(token(6), token(7), token(99), 10, TIME, token(9)),
                new OccurrenceClaim(token(6), token(7), token(8), 11, TIME, token(9)),
                new OccurrenceClaim(token(6), token(7), token(8), 10, TIME.plusMillis(1), token(9)),
                new OccurrenceClaim(token(6), token(7), token(8), 10, TIME, token(99)))) {
            var policy = new MerchantRiskSignalPolicy(WINDOW, WINDOW, new TestOnlyProfileAdmission(Set.of(new Binding(SCOPE, expected, profile))));
            allBlocked(policy.evaluate(SCOPE, expected, profile), MERCHANT_OCCURRENCE_MISMATCH);
        }
    }

    @Test
    void exactAnalysisWindowsCannotBeShortenedToManufactureNoveltyOrSilentlyRebased() {
        MerchantProfile profile = profile(0, 0);
        for (Duration recent : List.of(WINDOW.minusMillis(1), WINDOW.plusMillis(1))) {
            var policy = new MerchantRiskSignalPolicy(recent, WINDOW, admission(profile));
            allBlocked(policy.evaluate(SCOPE, OCCURRENCE, profile), MERCHANT_WINDOW_MISMATCH);
        }
        for (Duration baseline : List.of(WINDOW.minusMillis(1), WINDOW.plusMillis(1))) {
            var policy = new MerchantRiskSignalPolicy(WINDOW, baseline, admission(profile));
            allBlocked(policy.evaluate(SCOPE, OCCURRENCE, profile), MERCHANT_WINDOW_MISMATCH);
        }
    }

    @Test
    void allCombinationsOfWindowAvailabilityKeepMissingSeparateFromFalse() {
        for (MerchantProfile.Reason reason : List.of(MerchantProfile.Reason.MISSING_COVERAGE, MerchantProfile.Reason.RETENTION_EXPIRED)) {
            for (boolean recentAvailable : new boolean[] {false, true}) {
                for (boolean baselineAvailable : new boolean[] {false, true}) {
                    MerchantProfile profile = profile(recentAvailable ? available(TIME.minus(WINDOW), TIME, 0)
                                    : unavailable(TIME.minus(WINDOW), TIME, reason),
                            baselineAvailable ? available(TIME.minus(WINDOW).minus(WINDOW), TIME.minus(WINDOW), 0)
                                    : unavailable(TIME.minus(WINDOW).minus(WINDOW), TIME.minus(WINDOW), reason));
                    Evaluation result = evaluate(profile);
                    SignalOutcome novelty = outcome(result, Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW);
                    assertThat(novelty.observed()).isEqualTo(recentAvailable ? true : null);
                    assertThat(novelty.state()).isEqualTo(recentAvailable ? State.AVAILABLE : State.UNAVAILABLE);
                    SignalOutcome spike = outcome(result, Signal.MERCHANT_ACTIVITY_SPIKE);
                    assertThat(spike.observed()).isNull();
                    assertThat(spike.state()).isEqualTo(recentAvailable && baselineAvailable ? State.UNSUPPORTED : State.UNAVAILABLE);
                    assertThat(spike.reason()).isEqualTo(recentAvailable && baselineAvailable ? MERCHANT_SPIKE_METHOD_NOT_VALIDATED
                            : MerchantRiskSignalReasonCode.fromProfile(reason));
                    assertThat(result.score()).isNull();
                }
            }
        }
    }

    @Test
    void globalGapsConflictsMissingHistoryOrResourceBreachesInvalidateEvenAnOtherwiseAvailableZero() {
        for (MerchantProfile.Reason reason : MerchantProfile.Reason.values()) {
            if (reason == MerchantProfile.Reason.NONE || reason == MerchantProfile.Reason.MISSING_COVERAGE
                    || reason == MerchantProfile.Reason.RETENTION_EXPIRED) { continue; }
            MerchantProfile profile = profile(available(TIME.minus(WINDOW), TIME, 0),
                    unavailable(TIME.minus(WINDOW).minus(WINDOW), TIME.minus(WINDOW), reason));
            allBlocked(evaluate(profile), MerchantRiskSignalReasonCode.fromProfile(reason));
        }
    }

    @Test
    void clientSyntheticAndUnspecifiedClaimsCanNeverBorrowTheAdmittedProfilePath() {
        var policy = policy(profile(0, 0));
        for (SourceAssertion assertion : SourceAssertion.values()) {
            var claim = claim(assertion);
            Evaluation result = policy.evaluateClaim(claim);
            allBlocked(result, switch (assertion) {
                case SYNTHETIC -> MERCHANT_SYNTHETIC_INPUT;
                case CLIENT_ASSERTED -> MERCHANT_CLIENT_ASSERTION;
                case UNSPECIFIED -> MERCHANT_NOT_ADMITTED;
            });
        }
        allBlocked(policy.evaluateClaim(null), MERCHANT_NO_PROFILE);
    }

    @Test
    void oversizedProfileIsRejectedBeforePolicyEvaluation() {
        assertThatThrownBy(() -> profile(25_000, 1)).hasMessage("MERCHANT_PROFILE_COUNT_LIMIT");
    }

    @Test
    void diagnosticEvidencePreservesWindowAndCoverageExplanationWithoutSensitiveBindings() {
        Evaluation result = evaluate(profile(0, 5));
        assertThat(result.evidence().coverageStart()).isEqualTo(PROVENANCE.coverageStart());
        assertThat(result.evidence().watermark()).isEqualTo(PROVENANCE.watermark());
        assertThat(result.evidence().retainedFrom()).isEqualTo(PROVENANCE.retainedFrom());
        assertThat(result.evidence().evaluatedAt()).isEqualTo(PROVENANCE.evaluatedAt());
        assertThat(result.evidence().computationVersion()).isEqualTo(MerchantProfile.COMPUTATION_VERSION);
        for (int id = 1; id <= 10; id++) { assertThat(result.toString()).doesNotContain(token(id)); }
        assertThat(result.toString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
    }

    @Test
    void replayIsDeterministicAndTheFiveResultsHaveImmutableCanonicalOrder() {
        MerchantProfile profile = profile(1, 2);
        MerchantRiskSignalPolicy policy = policy(profile);
        Evaluation result = policy.evaluate(SCOPE, OCCURRENCE, profile);
        for (int replay = 0; replay < 10; replay++) {
            assertThat(policy.evaluate(SCOPE, OCCURRENCE, profile)).isEqualTo(result);
        }
        assertThat(result.signals()).extracting(SignalOutcome::signal).containsExactly(Signal.values());
        assertThatThrownBy(() -> result.signals().clear()).isInstanceOf(UnsupportedOperationException.class);
        List<SignalOutcome> mutable = new ArrayList<>(result.signals());
        Evaluation copy = new Evaluation(1, result.evidence(), mutable);
        mutable.clear();
        assertThat(copy).isEqualTo(result);
    }

    @Test
    void unsupportedAndUnavailableOutcomesCannotCarryFalseTrueOrANumericRisk() {
        for (SignalOutcome outcome : evaluate(profile(0, 0)).signals().subList(0, 4)) {
            for (boolean value : new boolean[] {false, true}) {
                assertThatThrownBy(() -> new SignalOutcome(outcome.signal(), outcome.state(), value, outcome.reason(), outcome.coverage()))
                        .hasMessage("MERCHANT_POLICY_OUTCOME_VALUE");
            }
        }
        assertThatThrownBy(() -> new SignalOutcome(Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW, State.AVAILABLE, false,
                MERCHANT_FIRST_OBSERVED_IN_WINDOW, Coverage.COMPLETE_RECENT_WINDOW)).hasMessage("MERCHANT_POLICY_OUTCOME_VALUE");
        assertThatThrownBy(() -> new SignalOutcome(Signal.MERCHANT_FRAUD_RATE, State.AVAILABLE, true,
                MERCHANT_FIRST_OBSERVED_IN_WINDOW, Coverage.COMPLETE_RECENT_WINDOW)).hasMessage("MERCHANT_POLICY_OUTCOME_REASON");
        assertThatThrownBy(() -> new SignalOutcome(Signal.MERCHANT_FIRST_OBSERVED_IN_WINDOW, State.UNTRUSTED, null,
                MERCHANT_SYNTHETIC_INPUT, Coverage.COMPLETE_RECENT_WINDOW)).hasMessage("MERCHANT_POLICY_OUTCOME_COVERAGE");
        assertThatThrownBy(() -> new SignalOutcome(Signal.MERCHANT_ACTIVITY_SPIKE, State.UNSUPPORTED, null,
                MERCHANT_SPIKE_METHOD_NOT_VALIDATED, Coverage.COMPLETE_RECENT_WINDOW)).hasMessage("MERCHANT_POLICY_OUTCOME_COVERAGE");
    }

    @Test
    void resultValidationRejectsMissingOrReorderedSignalsAndUnbackedAvailability() {
        Evaluation valid = evaluate(profile(0, 0));
        assertThatThrownBy(() -> new Evaluation(2, valid.evidence(), valid.signals())).hasMessage("MERCHANT_POLICY_EVALUATION");
        assertThatThrownBy(() -> new Evaluation(1, valid.evidence(), valid.signals().subList(0, 4))).hasMessage("MERCHANT_POLICY_EVALUATION");
        List<SignalOutcome> reversed = new ArrayList<>(valid.signals());
        Collections.reverse(reversed);
        assertThatThrownBy(() -> new Evaluation(1, valid.evidence(), reversed)).hasMessage("MERCHANT_POLICY_SIGNAL_ORDER");
        assertThatThrownBy(() -> new Evaluation(1, null, valid.signals())).hasMessage("MERCHANT_POLICY_EVIDENCE");
    }

    @Test
    void analysisWindowsHaveNoImplicitProductionDefaultsAndRespectSoftwareBounds() {
        for (Duration bad : Arrays.asList(null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1), Duration.ofDays(31))) {
            assertThatThrownBy(() -> new MerchantRiskSignalPolicy(bad, WINDOW, null)).hasMessage("MERCHANT_POLICY_WINDOWS");
            assertThatThrownBy(() -> new MerchantRiskSignalPolicy(WINDOW, bad, null)).hasMessage("MERCHANT_POLICY_WINDOWS");
        }
        assertThatThrownBy(() -> new MerchantRiskSignalPolicy(Duration.ofDays(30), WINDOW, null)).hasMessage("MERCHANT_POLICY_WINDOWS");
    }

    @Test
    void reasonOwnershipRemainsInternalAndNeverAliasesRulesConcentrationOrPublicEi() {
        for (MerchantRiskSignalReasonCode reason : MerchantRiskSignalReasonCode.values()) {
            assertThat(MerchantRiskSignalReasonCode.owns(reason.wireValue())).isTrue();
            assertThat(reason.wireValue()).matches("MERCHANT_[A-Z_]+").hasSizeLessThanOrEqualTo(80);
            assertThat(EngineIntelligenceReasonCodePolicy.isKnownReasonCode(reason.wireValue())).isFalse();
            for (String engine : FraudEngineIdentityContract.engineOrder()) {
                assertThat(EngineIntelligenceReasonCodePolicy.isAllowedForEngine(engine, reason.wireValue())).isFalse();
            }
        }
        assertThat(MerchantRiskSignalReasonCode.owns("MERCHANT_CONCENTRATION")).isFalse();
        assertThat(MerchantRiskSignalReasonCode.owns(null)).isFalse();
        assertThat(FraudEngineIdentityContract.isKnownEngineId("merchant.primary")).isFalse();
        assertThat(FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION).isEqualTo(2);
        assertThat(FraudEngineIdentityContract.maxEngineIntelligenceEngines()).isEqualTo(4);
    }

    private static Evaluation evaluate(MerchantProfile profile) { return policy(profile).evaluate(SCOPE, OCCURRENCE, profile); }
    private static MerchantRiskSignalPolicy policy(MerchantProfile profile) { return new MerchantRiskSignalPolicy(WINDOW, WINDOW, admission(profile)); }
    private static TestOnlyProfileAdmission admission(MerchantProfile profile) {
        return new TestOnlyProfileAdmission(Set.of(new Binding(SCOPE, OCCURRENCE, profile)));
    }
    private static SignalOutcome outcome(Evaluation result, Signal signal) { return result.signals().get(signal.ordinal()); }
    private static void assertOutcome(Evaluation result, Signal signal, State state, Boolean observed, MerchantRiskSignalReasonCode reason) {
        assertThat(outcome(result, signal).state()).isEqualTo(state);
        assertThat(outcome(result, signal).observed()).isEqualTo(observed);
        assertThat(outcome(result, signal).reason()).isEqualTo(reason);
        assertThat(outcome(result, signal).reasonCode()).isEqualTo(reason.wireValue());
    }
    private static void allBlocked(Evaluation result, MerchantRiskSignalReasonCode reason) {
        assertThat(result.evidence()).isNull();
        assertThat(result.score()).isNull();
        assertThat(result.signals()).hasSize(5).allSatisfy(signal -> {
            assertThat(signal.state()).isEqualTo(reason.state());
            assertThat(signal.reason()).isEqualTo(reason);
            assertThat(signal.observed()).isNull();
            assertThat(signal.coverage()).isEqualTo(Coverage.NO_USABLE_HISTORY);
        });
    }
    private static MerchantProfile profile(int recent, int baseline) {
        return profile(available(TIME.minus(WINDOW), TIME, recent), available(TIME.minus(WINDOW).minus(WINDOW), TIME.minus(WINDOW), baseline));
    }
    private static MerchantProfile profile(WindowCount recent, WindowCount baseline) {
        return new MerchantProfile(MerchantProfile.VERSION, MerchantProfile.COMPUTATION_VERSION,
                SCOPE, OCCURRENCE, PROVENANCE, recent, baseline);
    }
    private static WindowCount available(Instant from, Instant to, int count) {
        return new WindowCount(from, to, MerchantProfile.Status.AVAILABLE, count, MerchantProfile.Reason.NONE);
    }
    private static WindowCount unavailable(Instant from, Instant to, MerchantProfile.Reason reason) {
        return new WindowCount(from, to, MerchantProfile.Status.UNAVAILABLE, null, reason);
    }
    private static MerchantRiskEvidenceClaim claim(SourceAssertion assertion) {
        var state = assertion == SourceAssertion.UNSPECIFIED ? MerchantRiskEvidenceClaim.Status.UNAVAILABLE : MerchantRiskEvidenceClaim.Status.UNTRUSTED;
        var reason = switch (assertion) {
            case UNSPECIFIED -> MerchantRiskEvidenceClaim.Reason.NOT_ADMITTED;
            case CLIENT_ASSERTED -> MerchantRiskEvidenceClaim.Reason.CLIENT_ASSERTION;
            case SYNTHETIC -> MerchantRiskEvidenceClaim.Reason.SYNTHETIC_INPUT;
        };
        return new MerchantRiskEvidenceClaim(1, SCOPE, OCCURRENCE, new MerchantRiskEvidenceClaim.SourceClaim(assertion, TIME),
                new MerchantRiskEvidenceClaim.HistoryWindowClaim(TIME.minus(WINDOW), TIME, null, null, MerchantRiskEvidenceClaim.CoverageClaim.INCOMPLETE),
                Arrays.stream(Signal.values()).map(signal -> new MerchantRiskEvidenceClaim.SignalFact(signal, state, reason)).toList());
    }
    private static String token(int value) { return String.format("%064x", value); }

    private record Binding(ScopeClaim scope, OccurrenceClaim occurrence, MerchantProfile profile) { }

    /** TEST-ONLY exact profile and occurrence authorization assumption. No issuer authentication or runtime provider is claimed. */
    private record TestOnlyProfileAdmission(Set<Binding> bindings) implements MerchantRiskSignalPolicy.ProfileAdmission {
        public void requireProfile(ScopeClaim scope, OccurrenceClaim occurrence, MerchantProfile profile) {
            if (!bindings.contains(new Binding(scope, occurrence, profile))) { throw new SecurityException("TEST_ONLY_PROFILE_NOT_ADMITTED"); }
        }
    }
}
