package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import org.springframework.stereotype.Component;

@Component
public class ScoringEvidenceSnapshotMapper {

    public EvidenceType mapType(ScoringEvidenceType type) {
        if (type == null) {
            return EvidenceType.DIAGNOSTIC;
        }
        return switch (type) {
            case TRANSACTION_FEATURE -> EvidenceType.TRANSACTION_FEATURE;
            case CUSTOMER_BEHAVIOR -> EvidenceType.CUSTOMER_BEHAVIOR;
            case DEVICE_SIGNAL -> EvidenceType.DEVICE_SIGNAL;
            case GEO_SIGNAL -> EvidenceType.GEO_SIGNAL;
            case VELOCITY_SIGNAL -> EvidenceType.VELOCITY_SIGNAL;
            case MERCHANT_SIGNAL -> EvidenceType.MERCHANT_SIGNAL;
            case RULE_MATCH -> EvidenceType.RULE_MATCH;
            case MODEL_EXPLANATION -> EvidenceType.MODEL_EXPLANATION;
            case SCORING_SNAPSHOT -> EvidenceType.SCORING_SNAPSHOT;
            case DIAGNOSTIC -> EvidenceType.DIAGNOSTIC;
        };
    }

    public EvidenceStatus mapStatus(ScoringEvidenceStatus status) {
        if (status == null) {
            return EvidenceStatus.UNAVAILABLE;
        }
        return switch (status) {
            case AVAILABLE -> EvidenceStatus.AVAILABLE;
            case PARTIAL -> EvidenceStatus.PARTIAL;
            case UNAVAILABLE -> EvidenceStatus.UNAVAILABLE;
            case ERROR -> EvidenceStatus.ERROR;
            case NOT_APPLICABLE -> EvidenceStatus.NOT_APPLICABLE;
            case LEGACY -> EvidenceStatus.LEGACY;
        };
    }

    public EvidenceSeverity mapSeverity(ScoringEvidenceSeverity severity) {
        if (severity == null) {
            return EvidenceSeverity.LOW;
        }
        return switch (severity) {
            case LOW -> EvidenceSeverity.LOW;
            case MEDIUM -> EvidenceSeverity.MEDIUM;
            case HIGH -> EvidenceSeverity.HIGH;
            case CRITICAL -> EvidenceSeverity.CRITICAL;
        };
    }

    public EvidenceSource mapSource(ScoringEvidenceSource source) {
        if (source == null) {
            return EvidenceSource.FRAUD_SCORING_SERVICE;
        }
        return switch (source) {
            case RULE_BASED_SCORING, SCORING_FALLBACK -> EvidenceSource.FRAUD_SCORING_SERVICE;
            case ML_MODEL, ML_RUNTIME -> EvidenceSource.ML_INFERENCE_SERVICE;
            case FEATURE_SNAPSHOT -> EvidenceSource.FEATURE_ENRICHER;
            case LEGACY_SCORING -> EvidenceSource.LEGACY_SCORING_PAYLOAD;
        };
    }
}
