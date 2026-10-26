package com.frauddetection.scoring.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.common.events.device.DeviceRiskEvidence.HistoryFact;
import com.frauddetection.common.events.device.DeviceRiskEvidence.Reason;
import com.frauddetection.common.events.device.DeviceRiskEvidence.Source;
import com.frauddetection.common.events.device.DeviceRiskEvidence.SourceQuality;
import com.frauddetection.common.events.device.DeviceRiskEvidence.State;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationPolicy;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineEvidenceSanitizer;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineReasonCodeNormalizer;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceRiskSignalEvaluationMapperTest {
    private static final Instant TIME = Instant.parse("2026-10-26T10:05:00Z");

    @Test
    void emitsBoundedPerSignalDiagnosticsWithoutScoreOrIdentityMaterial() {
        DeviceRiskSignalPolicy.Evaluation policyResult = new DeviceRiskSignalPolicy().evaluate(evidence());

        FraudSignalEvaluation result = new DeviceRiskSignalEvaluationMapper().map(policyResult);

        assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.MEDIUM);
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.statusReason()).isEqualTo("DEVICE_SCORE_NOT_CALIBRATED");
        assertThat(result.reasonCodes()).containsExactly(
                "DEVICE_NOVELTY_NOT_SEEN_IN_WINDOW",
                "DEVICE_SWITCH_OBSERVED",
                "DEVICE_SHARED_DEVICE_UNSUPPORTED_SOURCE",
                "DEVICE_COUNTRY_MISMATCH_UNSUPPORTED_SOURCE",
                "DEVICE_PROXY_OR_VPN_UNSUPPORTED_SOURCE");
        assertThat(result.evidence()).hasSize(5).allSatisfy(item -> {
            assertThat(item.evidenceType()).isEqualTo(FraudEngineEvidenceType.DEVICE_SIGNAL);
            assertThat(item.source()).isEqualTo("DEVICE_RISK");
            assertThat(item.reasonCode()).isNotBlank();
            assertThat(item.description()).doesNotContain("a".repeat(64), "b".repeat(64), "c".repeat(64));
        });

        var warnings = new ArrayList<com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationWarning>();
        var normalizedCodes = new FraudEngineReasonCodeNormalizer().normalize("device.primary", result.reasonCodes(),
                FraudEngineAggregationPolicy.defaultInternalPolicy(), warnings);
        var normalizedEvidence = new FraudEngineEvidenceSanitizer().sanitize("device.primary", result.evidence(),
                FraudEngineAggregationPolicy.defaultInternalPolicy(), warnings);
        assertThat(normalizedCodes).containsExactlyElementsOf(result.reasonCodes());
        assertThat(normalizedEvidence).hasSize(5).allSatisfy(item -> {
            assertThat(item.evidenceType()).isEqualTo(FraudEngineEvidenceType.DEVICE_SIGNAL);
            assertThat(item.source()).isEqualTo("DEVICE_RISK");
        });
        assertThat(warnings).isEmpty();
    }

    @Test
    void unavailableAndUntrustedEvidenceKeepPerSignalReasonCodes() {
        DeviceRiskEvidence base = evidence();
        DeviceRiskEvidence incomplete = new DeviceRiskEvidence(base.schemaVersion(), base.occurrenceKey(),
                base.institutionToken(), base.customerToken(), base.deviceToken(), base.keyVersion(), base.source(),
                base.eventTime(), base.windowStart(), base.asOf(), null,
                new HistoryFact(State.UNAVAILABLE, Reason.INCOMPLETE_HISTORY, null),
                base.otherCustomers(), new HistoryFact(State.INVALID, Reason.INVALID_PROVENANCE, null),
                base.country(), base.network());
        var unavailable = new DeviceRiskSignalEvaluationMapper().map(new DeviceRiskSignalPolicy().evaluate(incomplete));
        assertThat(unavailable.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(unavailable.reasonCodes()).containsExactly(
                "DEVICE_NOVELTY_HISTORY_INCOMPLETE",
                "DEVICE_SWITCH_HISTORY_INVALID",
                "DEVICE_SHARED_DEVICE_UNSUPPORTED_SOURCE",
                "DEVICE_COUNTRY_MISMATCH_UNSUPPORTED_SOURCE",
                "DEVICE_PROXY_OR_VPN_UNSUPPORTED_SOURCE");
        assertThat(unavailable.reasonCodes()).doesNotContain("DEVICE_SCORE_NOT_CALIBRATED");
        assertThat(unavailable.evidence()).extracting(item -> item.reasonCode())
                .containsExactlyElementsOf(unavailable.reasonCodes());
    }

    private static DeviceRiskEvidence evidence() {
        return new DeviceRiskEvidence(1, "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), 1,
                new Source(SourceQuality.AUTHENTICATED_INSTITUTION, "BANK", "e".repeat(64), TIME),
                TIME, TIME.minusSeconds(30L * 24 * 60 * 60), TIME, TIME,
                new HistoryFact(State.AVAILABLE, Reason.NONE, 0),
                new HistoryFact(State.UNAVAILABLE, Reason.NO_SOURCE, null),
                new HistoryFact(State.AVAILABLE, Reason.NONE, 1),
                new DeviceRiskEvidence.CountryFact(State.UNAVAILABLE, Reason.PROVIDER_MISSING, null, null, null, null),
                new DeviceRiskEvidence.NetworkFact(State.UNAVAILABLE, Reason.PROVIDER_MISSING, null, null, null, null));
    }
}
