package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.reason.ReasonCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Component
public class ReasonCodeEvidenceTypeMapper {

    private static final Map<ReasonCode, EvidenceType> MAPPINGS = mappings();

    public Optional<EvidenceType> mapSupported(ReasonCode reasonCode) {
        if (reasonCode == null || reasonCode == ReasonCode.UNKNOWN) {
            return Optional.empty();
        }
        return Optional.ofNullable(MAPPINGS.get(reasonCode));
    }

    private static Map<ReasonCode, EvidenceType> mappings() {
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
        return Map.copyOf(mappings);
    }
}
