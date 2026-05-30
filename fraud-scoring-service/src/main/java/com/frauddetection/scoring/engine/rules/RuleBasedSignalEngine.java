package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class RuleBasedSignalEngine implements FraudSignalEngine {

    private static final String ENGINE_ID = "rules.primary";
    private static final String ENGINE_LANGUAGE = "java";
    private static final String ENGINE_VERSION = "1.0.0";
    private static final String EVIDENCE_SOURCE = "RULES";

    private final FeatureSnapshotReaderFactory readerFactory;
    private final RuleBasedFraudScoringEngine productionRuleEngine;

    public RuleBasedSignalEngine(
            FeatureSnapshotReaderFactory readerFactory,
            RuleBasedFraudScoringEngine productionRuleEngine
    ) {
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory is required");
        this.productionRuleEngine = Objects.requireNonNull(productionRuleEngine, "productionRuleEngine is required");
    }

    @Override
    public FraudEngineResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        FeatureSnapshotReader reader = readerFactory.from(context);
        FeatureSnapshotValueStatus invalidStatus = validateFeatureSnapshot(reader);
        if (invalidStatus != null) {
            return degradedResultFor(invalidStatus, 0L, context.receivedAt());
        }
        FraudScoreResult productionResult = productionRuleEngine.score(FraudScoringRequest.from(context.transaction()));
        return availableResult(productionResult, context.receivedAt());
    }

    @Override
    public FraudEngineDescriptor descriptor() {
        return new FraudEngineDescriptor(ENGINE_ID, FraudEngineType.RULES, ENGINE_LANGUAGE, ENGINE_VERSION, true);
    }

    static FraudEngineResult degradedResultFor(FeatureSnapshotValueStatus status, long latencyMs, Instant generatedAt) {
        RuleBasedSignalReasonCode reasonCode = switch (status) {
            case INVALID_TYPE -> RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID;
            case WRONG_ACCESSOR -> throw new IllegalStateException("adapter feature accessor mismatch");
            case NOT_ALLOWED -> throw new IllegalStateException("adapter feature access policy violation");
            case PRESENT, MISSING -> throw new IllegalArgumentException("status is not a degraded feature status");
        };
        return new FraudEngineResult(
                ENGINE_ID,
                FraudEngineType.RULES,
                ENGINE_LANGUAGE,
                FraudEngineStatus.DEGRADED,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of(reasonCode.wireValue()),
                List.of(),
                List.of(new FraudEngineEvidence(
                        FraudEngineEvidenceType.OPERATIONAL_FALLBACK,
                        reasonCode.wireValue(),
                        "Rule feature status",
                        "Bounded rule adapter diagnostic.",
                        EVIDENCE_SOURCE,
                        FraudEngineEvidenceStatus.PARTIAL
                )),
                latencyMs,
                null,
                null,
                reasonCode.wireValue(),
                generatedAt
        );
    }

    private FraudEngineResult availableResult(FraudScoreResult productionResult, Instant generatedAt) {
        List<String> reasonCodes = ReasonCode.supportedWireValues(
                ReasonCode.parseLegacyList(productionResult.reasonCodes())
        );
        return new FraudEngineResult(
                ENGINE_ID,
                FraudEngineType.RULES,
                ENGINE_LANGUAGE,
                FraudEngineStatus.AVAILABLE,
                productionResult.fraudScore(),
                productionResult.riskLevel(),
                reasonCodes.isEmpty() ? FraudEngineConfidence.LOW : FraudEngineConfidence.MEDIUM,
                reasonCodes,
                contributionsFor(reasonCodes),
                evidenceFor(reasonCodes),
                0L,
                productionResult.modelName(),
                productionResult.modelVersion(),
                null,
                generatedAt
        );
    }

    private FeatureSnapshotValueStatus validateFeatureSnapshot(FeatureSnapshotReader reader) {
        return firstInvalidType(
                reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY),
                reader.booleanValue(FraudFeatureContract.COUNTRY_MISMATCH),
                reader.booleanValue(FraudFeatureContract.PROXY_OR_VPN_DETECTED),
                reader.booleanValue(FraudFeatureContract.RAPID_TRANSFER_BURST),
                reader.booleanValue(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE),
                reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT),
                reader.integerValue(FraudFeatureContract.HIGH_RISK_FLAG_COUNT),
                reader.integerValue(FraudFeatureContract.RAPID_TRANSFER_COUNT),
                reader.doubleValue(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE),
                reader.decimalValue(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN),
                reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN),
                reader.decimalValue(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN)
        );
    }

    private FeatureSnapshotValueStatus firstInvalidType(FeatureSnapshotValue<?>... values) {
        for (FeatureSnapshotValue<?> value : values) {
            FeatureSnapshotValueStatus status = value.status();
            if (status == FeatureSnapshotValueStatus.WRONG_ACCESSOR) {
                throw new IllegalStateException("adapter feature accessor mismatch");
            }
            if (status == FeatureSnapshotValueStatus.NOT_ALLOWED) {
                throw new IllegalStateException("adapter feature access policy violation");
            }
            if (status == FeatureSnapshotValueStatus.INVALID_TYPE) {
                return status;
            }
        }
        return null;
    }

    private List<FraudEngineContribution> contributionsFor(List<String> reasonCodes) {
        return reasonCodes.stream()
                .map(reasonCode -> new FraudEngineContribution(
                        reasonCode,
                        null,
                        null,
                        FraudEngineContributionDirection.UNKNOWN
                ))
                .toList();
    }

    private List<FraudEngineEvidence> evidenceFor(List<String> reasonCodes) {
        return reasonCodes.stream()
                .map(reasonCode -> new FraudEngineEvidence(
                        evidenceTypeFor(reasonCode),
                        reasonCode,
                        titleFor(reasonCode),
                        "Bounded rule scoring signal.",
                        EVIDENCE_SOURCE,
                        FraudEngineEvidenceStatus.AVAILABLE
                ))
                .toList();
    }

    private FraudEngineEvidenceType evidenceTypeFor(String reasonCode) {
        return ReasonCode.known(reasonCode)
                .map(ReasonCode::category)
                .map(category -> switch (category) {
                    case DEVICE_AND_NETWORK -> FraudEngineEvidenceType.DEVICE_SIGNAL;
                    case VELOCITY -> FraudEngineEvidenceType.VELOCITY_SIGNAL;
                    case MERCHANT -> FraudEngineEvidenceType.MERCHANT_SIGNAL;
                    case AMOUNT, CUSTOMER_BEHAVIOR -> FraudEngineEvidenceType.RULE_MATCH;
                    case MODEL_RUNTIME, UNSUPPORTED -> FraudEngineEvidenceType.OPERATIONAL_FALLBACK;
                })
                .orElse(FraudEngineEvidenceType.RULE_MATCH);
    }

    private String titleFor(String reasonCode) {
        return ReasonCode.known(reasonCode)
                .map(ReasonCode::title)
                .orElse("Rule signal");
    }
}
