package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceReasonCodeMappingTest {

    private final ReasonCodeEvidenceTypeMapper mapper = new ReasonCodeEvidenceTypeMapper();

    @Test
    void everySupportedReasonCodeMapsToExpectedEvidenceType() {
        Map<ReasonCode, EvidenceType> expected = expectedMappings();

        for (ReasonCode reasonCode : ReasonCode.values()) {
            if (reasonCode == ReasonCode.UNKNOWN) {
                assertThat(mapper.mapSupported(reasonCode)).isEmpty();
            } else {
                assertThat(mapper.mapSupported(reasonCode))
                        .as(reasonCode.name())
                        .contains(expected.get(reasonCode));
            }
        }
        assertThat(expected.keySet()).containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(ReasonCode.values())
                        .filter(reasonCode -> reasonCode != ReasonCode.UNKNOWN)
                        .toList()
        );
    }

    @Test
    void unknownDoesNotMapToSupportedEvidence() {
        assertThat(mapper.mapSupported(ReasonCode.UNKNOWN)).isEmpty();
        assertThat(mapper.mapSupported(null)).isEmpty();
    }

    private Map<ReasonCode, EvidenceType> expectedMappings() {
        EnumMap<ReasonCode, EvidenceType> mappings = new EnumMap<>(ReasonCode.class);
        mappings.put(ReasonCode.DEVICE_NOVELTY, EvidenceType.DEVICE_SIGNAL);
        mappings.put(ReasonCode.PROXY_OR_VPN, EvidenceType.DEVICE_SIGNAL);
        mappings.put(ReasonCode.COUNTRY_MISMATCH, EvidenceType.GEO_SIGNAL);
        mappings.put(ReasonCode.HIGH_VELOCITY, EvidenceType.VELOCITY_SIGNAL);
        mappings.put(ReasonCode.TRANSACTION_VELOCITY, EvidenceType.VELOCITY_SIGNAL);
        mappings.put(ReasonCode.RECENT_TRANSACTION_SPIKE, EvidenceType.VELOCITY_SIGNAL);
        mappings.put(ReasonCode.RAPID_PLN_20K_BURST, EvidenceType.VELOCITY_SIGNAL);
        mappings.put(ReasonCode.RAPID_TRANSFER_FRAUD_CASE, EvidenceType.VELOCITY_SIGNAL);
        mappings.put(ReasonCode.HIGH_TRANSACTION_AMOUNT, EvidenceType.TRANSACTION_FEATURE);
        mappings.put(ReasonCode.RECENT_AMOUNT_ACCUMULATION, EvidenceType.TRANSACTION_FEATURE);
        mappings.put(ReasonCode.HIGH_AMOUNT_ACTIVITY, EvidenceType.TRANSACTION_FEATURE);
        mappings.put(ReasonCode.MERCHANT_CONCENTRATION, EvidenceType.MERCHANT_SIGNAL);
        mappings.put(ReasonCode.ML_MODEL_UNAVAILABLE, EvidenceType.MODEL_EXPLANATION);
        mappings.put(ReasonCode.LOW_MODEL_RISK, EvidenceType.MODEL_EXPLANATION);
        mappings.put(ReasonCode.MODEL_HIGH_RISK, EvidenceType.MODEL_EXPLANATION);
        return mappings;
    }
}
