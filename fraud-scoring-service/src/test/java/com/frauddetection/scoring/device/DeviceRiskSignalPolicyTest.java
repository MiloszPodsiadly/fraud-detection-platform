package com.frauddetection.scoring.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.common.events.device.DeviceRiskEvidence.HistoryFact;
import com.frauddetection.common.events.device.DeviceRiskEvidence.Reason;
import com.frauddetection.common.events.device.DeviceRiskEvidence.Source;
import com.frauddetection.common.events.device.DeviceRiskEvidence.SourceQuality;
import com.frauddetection.common.events.device.DeviceRiskEvidence.State;
import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceRiskSignalPolicyTest {
    private static final Instant TIME = Instant.parse("2026-10-26T10:05:00Z");
    private final DeviceRiskSignalPolicy policy = new DeviceRiskSignalPolicy();

    @Test
    void evaluatesAllCountBoundariesWithoutInventingAWeightedScore() {
        for (int prior : new int[]{0, 1, DeviceRiskEvidence.MAX_OBSERVATIONS}) {
            for (int switches : new int[]{0, 1, DeviceRiskEvidence.MAX_OBSERVATIONS}) {
                var result = policy.evaluate(evidence(available(prior), available(switches),
                        Duration.ofDays(30), TIME, TIME, SourceQuality.AUTHENTICATED_INSTITUTION));
                assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
                assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.MEDIUM);
                assertThat(result.score()).isNull();
                assertThat(result.signals().get(0).observed()).isEqualTo(prior == 0);
                assertThat(result.signals().get(1).observed()).isEqualTo(switches > 0);
                assertThat(result.signals().get(0).reason()).isEqualTo(prior == 0
                        ? DeviceRiskSignalPolicy.Reason.NOT_SEEN_IN_WINDOW
                        : DeviceRiskSignalPolicy.Reason.SEEN_IN_WINDOW);
                assertThat(result.signals().get(1).reason()).isEqualTo(switches > 0
                        ? DeviceRiskSignalPolicy.Reason.SWITCH_OBSERVED
                        : DeviceRiskSignalPolicy.Reason.NO_SWITCH_OBSERVED);
            }
        }
    }

    @Test
    void allFactStateCombinationsRemainCoherentAndDoNotTurnMissingIntoFalse() {
        HistoryFact[] facts = {available(0), available(1),
                new HistoryFact(State.UNAVAILABLE, Reason.INCOMPLETE_HISTORY, null),
                new HistoryFact(State.UNAVAILABLE, Reason.KEY_UNAVAILABLE, null),
                new HistoryFact(State.UNTRUSTED, Reason.CLIENT_ASSERTION, null),
                new HistoryFact(State.INVALID, Reason.INVALID_PROVENANCE, null)};
        for (HistoryFact prior : facts) {
            for (HistoryFact switches : facts) {
                var result = policy.evaluate(evidence(prior, switches, Duration.ofDays(30), TIME,
                        TIME, SourceQuality.AUTHENTICATED_INSTITUTION));
                int count = (prior.state() == State.AVAILABLE ? 1 : 0)
                        + (switches.state() == State.AVAILABLE ? 1 : 0);
                assertThat(result.status()).isEqualTo(count == 2 ? FraudEngineStatus.AVAILABLE
                        : count == 1 ? FraudEngineStatus.DEGRADED : FraudEngineStatus.UNAVAILABLE);
                assertThat(result.confidence()).isEqualTo(count == 2 ? FraudEngineConfidence.MEDIUM
                        : count == 1 ? FraudEngineConfidence.LOW : FraudEngineConfidence.UNKNOWN);
                assertThat(result.signals().subList(0, 2)).allSatisfy(signal -> {
                    assertThat(signal.observed() == null).isEqualTo(signal.status() != FraudEngineStatus.AVAILABLE);
                    assertThat(signal.confidence()).isEqualTo(signal.status() == FraudEngineStatus.AVAILABLE
                            ? FraudEngineConfidence.MEDIUM : FraudEngineConfidence.UNKNOWN);
                });
                assertThat(result.score()).isNull();
            }
        }
    }

    @Test
    void incompleteOrLateWindowCannotProveAnAbsence() {
        for (Duration window : new Duration[]{Duration.ofDays(29), Duration.ofDays(30).minusNanos(1)}) {
            var result = policy.evaluate(evidence(available(0), available(1), window, TIME, TIME,
                    SourceQuality.AUTHENTICATED_INSTITUTION));
            assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
            assertThat(result.signals().get(0).reason()).isEqualTo(DeviceRiskSignalPolicy.Reason.HISTORY_INCOMPLETE);
            assertThat(result.signals().get(0).observed()).isNull();
        }
        var late = policy.evaluate(evidence(available(0), available(1), Duration.ofDays(30), TIME,
                TIME.plusSeconds(1), SourceQuality.AUTHENTICATED_INSTITUTION));
        assertThat(late.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(late.signals().get(1).reason()).isEqualTo(DeviceRiskSignalPolicy.Reason.HISTORY_INCOMPLETE);
    }

    @Test
    void clientAndSyntheticSourcesAreSkippedAndCannotSmuggleAvailableFacts() {
        for (SourceQuality quality : new SourceQuality[]{SourceQuality.CLIENT_ASSERTED, SourceQuality.SYNTHETIC}) {
            var result = policy.evaluate(evidence(untrusted(quality), untrusted(quality),
                    Duration.ofDays(30), TIME, TIME, quality));
            assertThat(result.status()).isEqualTo(FraudEngineStatus.SKIPPED);
            assertThat(result.signals()).allSatisfy(signal -> {
                assertThat(signal.status()).isEqualTo(FraudEngineStatus.SKIPPED);
                assertThat(signal.observed()).isNull();
                assertThat(signal.reason()).isEqualTo(DeviceRiskSignalPolicy.Reason.UNTRUSTED_SOURCE);
            });
            assertThatThrownBy(() -> evidence(available(0), untrusted(quality), Duration.ofDays(30),
                    TIME, TIME, quality)).hasMessage("DEVICE_EVIDENCE_UNTRUSTED_AVAILABLE");
        }
    }

    @Test
    void unsupportedSourcesNeverInheritClaimsEvenIfFactsAreStructurallyAvailable() {
        var basic = evidence(available(0), available(1), Duration.ofDays(30), TIME, TIME,
                SourceQuality.AUTHENTICATED_INSTITUTION);
        var evidence = new DeviceRiskEvidence(basic.schemaVersion(), basic.occurrenceKey(),
                basic.institutionToken(), basic.customerToken(), basic.deviceToken(), basic.keyVersion(),
                basic.source(), basic.eventTime(), basic.windowStart(), basic.asOf(), basic.coverageThrough(),
                basic.priorDevice(), available(2), basic.switches(),
                new DeviceRiskEvidence.CountryFact(State.AVAILABLE, Reason.NONE, "PL", "DE", "GEO", TIME),
                new DeviceRiskEvidence.NetworkFact(State.AVAILABLE, Reason.NONE, true, true, "DETECTOR", TIME));
        var result = policy.evaluate(evidence);
        for (var signal : result.signals().subList(2, 5)) {
            assertThat(signal.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
            assertThat(signal.observed()).isNull();
            assertThat(signal.reason()).isEqualTo(DeviceRiskSignalPolicy.Reason.UNSUPPORTED_SOURCE);
        }
    }

    @Test
    void replayOrderPrivacyAndOutcomeValidationAreStable() {
        var evidence = evidence(available(0), available(1), Duration.ofDays(30), TIME, TIME,
                SourceQuality.AUTHENTICATED_INSTITUTION);
        var first = policy.evaluate(evidence);
        assertThat(policy.evaluate(evidence)).isEqualTo(first);
        assertThat(first.policyVersion()).isEqualTo(1);
        assertThat(first.signals()).extracting(DeviceRiskSignalPolicy.SignalOutcome::signal)
                .containsExactly(DeviceRiskSignalPolicy.Signal.values());
        String diagnostic = first.toString();
        assertThat(diagnostic).doesNotContain("a".repeat(64), "b".repeat(64), "c".repeat(64),
                "d".repeat(64), "trustedDevice", "192.0.2.1");
        assertThat(Arrays.stream(DeviceRiskSignalPolicy.Reason.values()).map(Enum::name))
                .allSatisfy(code -> assertThat(code).hasSizeLessThanOrEqualTo(32));
        assertThatThrownBy(() -> new DeviceRiskSignalPolicy.SignalOutcome(
                DeviceRiskSignalPolicy.Signal.DEVICE_SWITCH, FraudEngineStatus.UNAVAILABLE, false,
                DeviceRiskSignalPolicy.Reason.HISTORY_UNAVAILABLE, FraudEngineConfidence.UNKNOWN))
                .hasMessage("DEVICE_POLICY_OUTCOME_STATE");
        assertThatThrownBy(() -> new DeviceRiskSignalPolicy.SignalOutcome(
                DeviceRiskSignalPolicy.Signal.PROXY_OR_VPN, FraudEngineStatus.AVAILABLE, true,
                DeviceRiskSignalPolicy.Reason.SWITCH_OBSERVED, FraudEngineConfidence.MEDIUM))
                .hasMessage("DEVICE_POLICY_OUTCOME_REASON");
        assertThatThrownBy(() -> new DeviceRiskSignalPolicy.Evaluation(
                DeviceRiskSignalPolicy.VERSION,
                FraudEngineStatus.DEGRADED,
                FraudEngineConfidence.LOW,
                List.of(
                        new DeviceRiskSignalPolicy.SignalOutcome(
                                DeviceRiskSignalPolicy.Signal.DEVICE_NOT_SEEN_RECENTLY,
                                FraudEngineStatus.AVAILABLE, true,
                                DeviceRiskSignalPolicy.Reason.NOT_SEEN_IN_WINDOW, FraudEngineConfidence.MEDIUM),
                        new DeviceRiskSignalPolicy.SignalOutcome(
                                DeviceRiskSignalPolicy.Signal.DEVICE_SWITCH,
                                FraudEngineStatus.SKIPPED, null,
                                DeviceRiskSignalPolicy.Reason.UNTRUSTED_SOURCE, FraudEngineConfidence.UNKNOWN),
                        new DeviceRiskSignalPolicy.SignalOutcome(
                                DeviceRiskSignalPolicy.Signal.OTHER_CUSTOMERS,
                                FraudEngineStatus.UNAVAILABLE, null,
                                DeviceRiskSignalPolicy.Reason.UNSUPPORTED_SOURCE, FraudEngineConfidence.UNKNOWN),
                        new DeviceRiskSignalPolicy.SignalOutcome(
                                DeviceRiskSignalPolicy.Signal.COUNTRY_MISMATCH,
                                FraudEngineStatus.UNAVAILABLE, null,
                                DeviceRiskSignalPolicy.Reason.UNSUPPORTED_SOURCE, FraudEngineConfidence.UNKNOWN),
                        new DeviceRiskSignalPolicy.SignalOutcome(
                                DeviceRiskSignalPolicy.Signal.PROXY_OR_VPN,
                                FraudEngineStatus.UNAVAILABLE, null,
                                DeviceRiskSignalPolicy.Reason.UNSUPPORTED_SOURCE, FraudEngineConfidence.UNKNOWN)),
                null)).hasMessage("DEVICE_POLICY_MIXED_SOURCE");
    }

    private static DeviceRiskEvidence evidence(HistoryFact prior, HistoryFact switches, Duration window,
                                               Instant coverage, Instant observedAt, SourceQuality quality) {
        DeviceRiskEvidence.Reason missing = quality == SourceQuality.CLIENT_ASSERTED
                ? Reason.CLIENT_ASSERTION : quality == SourceQuality.SYNTHETIC
                ? Reason.SYNTHETIC_INPUT : Reason.NO_SOURCE;
        State missingState = quality == SourceQuality.AUTHENTICATED_INSTITUTION
                ? State.UNAVAILABLE : State.UNTRUSTED;
        return new DeviceRiskEvidence(1, "a".repeat(64), "b".repeat(64),
                quality == SourceQuality.AUTHENTICATED_INSTITUTION ? "c".repeat(64) : null,
                quality == SourceQuality.AUTHENTICATED_INSTITUTION ? "d".repeat(64) : null,
                1, new Source(quality, "BANK", "e".repeat(64), observedAt),
                TIME, TIME.minus(window), TIME, coverage, prior,
                new HistoryFact(missingState, missing, null), switches,
                new DeviceRiskEvidence.CountryFact(missingState, missing, null, null, null, null),
                new DeviceRiskEvidence.NetworkFact(missingState, missing, null, null, null, null));
    }

    private static HistoryFact available(int count) {
        return new HistoryFact(State.AVAILABLE, Reason.NONE, count);
    }

    private static HistoryFact untrusted(SourceQuality quality) {
        return new HistoryFact(State.UNTRUSTED, quality == SourceQuality.CLIENT_ASSERTED
                ? Reason.CLIENT_ASSERTION : Reason.SYNTHETIC_INPUT, null);
    }
}
