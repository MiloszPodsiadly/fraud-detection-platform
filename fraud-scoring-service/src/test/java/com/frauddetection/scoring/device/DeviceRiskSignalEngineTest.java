package com.frauddetection.scoring.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceRiskSignalEngineTest {
    private static final Instant TIME = Instant.parse("2026-10-26T10:05:00Z");

    @Test
    void descriptorIsOptionalButExistingPublicRegistryRejectsFourthIdentity() {
        var engine = new DeviceRiskSignalEngine(context -> Optional.empty(), new DeviceRiskSignalPolicy());
        assertThat(engine.descriptor().engineId()).isEqualTo("device.primary");
        assertThat(engine.descriptor().engineType()).isEqualTo(FraudEngineType.DEVICE_RISK);
        assertThat(engine.descriptor().engineLanguage()).isEqualTo("java");
        assertThat(engine.descriptor().version()).isEqualTo("device-v1");
        assertThat(engine.descriptor().required()).isFalse();
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(engine)))
                .hasMessage("ENGINE_REGISTRY_UNKNOWN_ENGINE_ID");
    }

    @Test
    void missingAndClientEvidenceNeverBecomeAnAvailableFraudScore() {
        var missing = new DeviceRiskSignalEngine(context -> Optional.empty(), new DeviceRiskSignalPolicy())
                .evaluate(context());
        assertThat(missing.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(missing.score()).isNull();
        assertThat(missing.riskLevel()).isNull();
        assertThat(missing.reasonCodes()).isEmpty();

        var client = new DeviceRiskSignalEngine(context -> Optional.of(evidence(
                DeviceRiskEvidence.SourceQuality.CLIENT_ASSERTED)), new DeviceRiskSignalPolicy())
                .evaluate(context());
        assertThat(client.status()).isEqualTo(FraudEngineStatus.SKIPPED);
        assertThat(client.score()).isNull();
        assertThat(client.reasonCodes()).isEmpty();
        assertThat(client.toString()).doesNotContain("a".repeat(64), "b".repeat(64), "trustedDevice");
    }

    @Test
    void isolatedAdmittedFixtureUsesPurePolicyWithoutPublicReasonsOrScore() {
        var engine = new DeviceRiskSignalEngine(context -> Optional.of(evidence(
                DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION)), new DeviceRiskSignalPolicy());
        var result = engine.evaluate(context());
        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.statusReason()).isEqualTo("DEVICE_SCORE_NOT_CALIBRATED");
        var internalResult = new FraudEngineResult(engine.descriptor().engineId(),
                engine.descriptor().engineType(), engine.descriptor().engineLanguage(),
                result.status(), result.score(), result.riskLevel(), result.confidence(),
                result.reasonCodes(), result.contributions(), result.evidence(), 1L,
                null, null, result.statusReason(), TIME);
        assertThat(internalResult.status()).isEqualTo(FraudEngineStatus.DEGRADED);
    }

    @Test
    void existingBoundedExecutorContainsCandidateFailureAndTimeout() throws Exception {
        var engine = new DeviceRiskSignalEngine(context -> { throw new IllegalStateException("private detail"); },
                new DeviceRiskSignalPolicy());
        try (var executor = new BoundedFraudEngineExecutor(1, 1)) {
            assertThat(executor.execute(() -> engine.evaluate(context()), Duration.ofMillis(50)).status())
                    .isEqualTo(BoundedFraudEngineExecutor.ExecutionStatus.FAILED);

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var timeout = executor.execute(() -> {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                return engine.evaluate(context());
            }, Duration.ofMillis(25));
            assertThat(entered.getCount()).isZero();
            assertThat(timeout.status()).isEqualTo(BoundedFraudEngineExecutor.ExecutionStatus.TIMED_OUT);
            release.countDown();
        }
    }

    private static ScoringContext context() {
        var transaction = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(transaction, Map.of(), ScoringMode.RULE_BASED,
                transaction.correlationId(), TIME);
    }

    private static DeviceRiskEvidence evidence(DeviceRiskEvidence.SourceQuality quality) {
        boolean admitted = quality == DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION;
        var reason = admitted ? DeviceRiskEvidence.Reason.NO_SOURCE : DeviceRiskEvidence.Reason.CLIENT_ASSERTION;
        var state = admitted ? DeviceRiskEvidence.State.UNAVAILABLE : DeviceRiskEvidence.State.UNTRUSTED;
        var absent = new DeviceRiskEvidence.HistoryFact(state, reason, null);
        return new DeviceRiskEvidence(1, "a".repeat(64), "b".repeat(64),
                admitted ? "c".repeat(64) : null, admitted ? "d".repeat(64) : null, 1,
                new DeviceRiskEvidence.Source(quality, "BANK", "e".repeat(64), TIME),
                TIME, TIME.minus(Duration.ofDays(30)), TIME, admitted ? TIME : null,
                admitted ? new DeviceRiskEvidence.HistoryFact(DeviceRiskEvidence.State.AVAILABLE,
                        DeviceRiskEvidence.Reason.NONE, 0) : absent,
                absent,
                admitted ? new DeviceRiskEvidence.HistoryFact(DeviceRiskEvidence.State.AVAILABLE,
                        DeviceRiskEvidence.Reason.NONE, 1) : absent,
                new DeviceRiskEvidence.CountryFact(state, reason, null, null, null, null),
                new DeviceRiskEvidence.NetworkFact(state, reason, null, null, null, null));
    }
}
