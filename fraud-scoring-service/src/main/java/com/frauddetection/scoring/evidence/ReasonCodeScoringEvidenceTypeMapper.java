package com.frauddetection.scoring.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.reason.ReasonCode;

import java.util.Optional;

public class ReasonCodeScoringEvidenceTypeMapper {

    public Optional<ScoringEvidenceType> map(ReasonCode reasonCode) {
        if (reasonCode == null || reasonCode == ReasonCode.UNKNOWN) {
            return Optional.empty();
        }
        return Optional.of(switch (reasonCode) {
            case DEVICE_NOVELTY, PROXY_OR_VPN -> ScoringEvidenceType.DEVICE_SIGNAL;
            case COUNTRY_MISMATCH -> ScoringEvidenceType.GEO_SIGNAL;
            case HIGH_VELOCITY,
                 TRANSACTION_VELOCITY,
                 RECENT_TRANSACTION_SPIKE,
                 RAPID_PLN_20K_BURST,
                 RAPID_TRANSFER_FRAUD_CASE -> ScoringEvidenceType.VELOCITY_SIGNAL;
            case HIGH_TRANSACTION_AMOUNT,
                 RECENT_AMOUNT_ACCUMULATION,
                 HIGH_AMOUNT_ACTIVITY -> ScoringEvidenceType.TRANSACTION_FEATURE;
            case MERCHANT_CONCENTRATION -> ScoringEvidenceType.MERCHANT_SIGNAL;
            case ML_MODEL_UNAVAILABLE,
                 LOW_MODEL_RISK,
                 MODEL_HIGH_RISK -> ScoringEvidenceType.MODEL_EXPLANATION;
            case UNKNOWN -> throw new IllegalStateException("UNKNOWN is diagnostic only.");
        });
    }
}
