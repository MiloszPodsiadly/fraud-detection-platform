package com.frauddetection.scoring.device;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceRiskSignalEngineTest {
    private static final Instant TIME = Instant.parse("2026-10-26T10:05:00Z");

    @Test
    void descriptorIsOptionalAndKnownButCoreEnginesRemainRequired() {
        var engine = new DeviceRiskSignalEngine();
        assertThat(engine.descriptor().engineId()).isEqualTo("device.primary");
        assertThat(engine.descriptor().engineType()).isEqualTo(FraudEngineType.DEVICE_RISK);
        assertThat(engine.descriptor().engineLanguage()).isEqualTo("java");
        assertThat(engine.descriptor().version()).isEqualTo("device-v1");
        assertThat(engine.descriptor().required()).isFalse();
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(engine)))
                .hasMessage("ENGINE_REGISTRY_REQUIRED_ENGINE_MISSING");
    }

    @Test
    void canonicalRegistrySupportsOptionalFourthDeviceEngine() {
        var registry = new FraudSignalEngineRegistry(List.of(
                stub("rules.primary", FraudEngineType.RULES, true),
                stub("ml.python.primary", FraudEngineType.ML_MODEL, true),
                stub("velocity.primary", FraudEngineType.VELOCITY, false),
                new DeviceRiskSignalEngine()));

        assertThat(registry.orderedEngines()).extracting(engine -> engine.descriptor().engineId())
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary", "device.primary");
        assertThat(FraudEngineIdentityContract.engineOrder())
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary", "device.primary");
    }

    @Test
    void registryRejectsRequiredDeviceDescriptor() {
        assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(
                stub("rules.primary", FraudEngineType.RULES, true),
                stub("ml.python.primary", FraudEngineType.ML_MODEL, true),
                stub("device.primary", FraudEngineType.DEVICE_RISK, true)
        ))).hasMessage("ENGINE_REGISTRY_OPTIONAL_ENGINE_REQUIRED");
    }

    @Test
    void engineHasNoReaderAndNeverInfersAdmissionFromScoringContext() {
        var engine = new DeviceRiskSignalEngine();
        assertThat(engine.getClass().getDeclaredConstructors())
                .anySatisfy(constructor -> assertThat(constructor.getParameterCount()).isZero());

        var result = engine.evaluate(context());
        assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(result.confidence()).isEqualTo(
                com.frauddetection.common.events.engine.FraudEngineConfidence.UNKNOWN);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.reasonCodes()).containsExactly("DEVICE_EVIDENCE_NOT_ADMITTED");
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.evidenceType()).isEqualTo(
                    com.frauddetection.common.events.engine.FraudEngineEvidenceType.DEVICE_SIGNAL);
            assertThat(evidence.reasonCode()).isEqualTo("DEVICE_EVIDENCE_NOT_ADMITTED");
            assertThat(evidence.description()).doesNotContain("transaction", "customer", "device-id");
        });
        assertThat(result.statusReason()).isEqualTo("DEVICE_EVIDENCE_NOT_ADMITTED");
    }

    private static ScoringContext context() {
        var transaction = TransactionFixtures.enrichedTransaction().build();
        DeviceInfo callerClaimedDevice = new DeviceInfo(
                transaction.deviceInfo().deviceId(),
                transaction.deviceInfo().fingerprint(),
                transaction.deviceInfo().ipAddress(),
                transaction.deviceInfo().userAgent(),
                transaction.deviceInfo().platform(),
                transaction.deviceInfo().browser(),
                true,
                true,
                true,
                Map.of("issuer", "BANK", "signature", "self-signed", "sourceQuality", "AUTHENTICATED_INSTITUTION")
        );
        TransactionEnrichedEvent callerClaimedTransaction = new TransactionEnrichedEvent(
                transaction.eventId(), transaction.transactionId(), transaction.correlationId(),
                transaction.customerId(), transaction.accountId(), transaction.createdAt(),
                transaction.transactionTimestamp(), transaction.transactionAmount(), transaction.merchantInfo(),
                callerClaimedDevice, transaction.locationInfo(), transaction.customerContext(),
                transaction.featureSnapshot());
        return new ScoringContext(callerClaimedTransaction, Map.of(), ScoringMode.RULE_BASED,
                callerClaimedTransaction.correlationId(), TIME);
    }

    private static FraudSignalEngine stub(String engineId, FraudEngineType type, boolean required) {
        return new FraudSignalEngine() {
            @Override
            public FraudSignalEvaluation evaluate(ScoringContext context) {
                return new FraudSignalEvaluation(FraudEngineStatus.UNAVAILABLE, null, null,
                        com.frauddetection.common.events.engine.FraudEngineConfidence.UNKNOWN,
                        List.of(), List.of(), List.of(), null, null, null);
            }

            @Override
            public FraudEngineDescriptor descriptor() {
                return new FraudEngineDescriptor(engineId, type, "java", "test-v1", required);
            }
        };
    }

}
