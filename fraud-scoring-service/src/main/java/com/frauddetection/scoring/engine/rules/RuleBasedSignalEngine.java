package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import com.frauddetection.scoring.service.RulesInputValidationResult;
import com.frauddetection.scoring.service.RulesInputValidationStatus;
import com.frauddetection.scoring.service.RulesV2InputValidator;
import com.frauddetection.scoring.service.RulesV2ValidatedInput;

import java.util.List;
import java.util.Objects;

public final class RuleBasedSignalEngine implements FraudSignalEngine {

    private static final String ENGINE_ID = FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID;
    private static final String ENGINE_LANGUAGE = "java";
    private static final String ENGINE_VERSION = "2.0.0";
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
    public FraudSignalEvaluation evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        FeatureSnapshotReader reader = readerFactory.from(context);
        RulesInputValidationResult validation = RulesV2InputValidator.validate(reader);
        validation.requireNoAdapterDefect();
        if (!validation.valid()) {
            return degradedResultFor(validation.status());
        }
        RulesV2ValidatedInput input = RulesV2InputValidator.requireValidInput(reader);
        FraudScoreResult productionResult = productionRuleEngine.scoreValidated(input);
        return availableResult(productionResult);
    }

    @Override
    public FraudEngineDescriptor descriptor() {
        return new FraudEngineDescriptor(ENGINE_ID, FraudEngineType.RULES, ENGINE_LANGUAGE, ENGINE_VERSION, true);
    }

    static FraudSignalEvaluation degradedResultFor(FeatureSnapshotValueStatus status) {
        RulesInputValidationStatus validationStatus = switch (status) {
            case INVALID_TYPE -> RulesInputValidationStatus.INVALID_TYPE;
            case WRONG_ACCESSOR -> RulesInputValidationStatus.ADAPTER_ACCESSOR_DEFECT;
            case NOT_ALLOWED -> RulesInputValidationStatus.ACCESS_POLICY_DEFECT;
            case PRESENT, MISSING -> throw new IllegalArgumentException("status is not a degraded feature status");
        };
        return degradedResultFor(validationStatus);
    }

    static FraudSignalEvaluation degradedResultFor(RulesInputValidationStatus status) {
        RuleBasedSignalReasonCode reasonCode = switch (status) {
            case INVALID_TYPE,
                 INVALID_WINDOW,
                 OUT_OF_BOUNDS,
                 INCONSISTENT_FACTS,
                 INCOMPLETE_FACT_PAIR,
                 UNSUPPORTED_CURRENCY_BASIS -> RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID;
            case ADAPTER_ACCESSOR_DEFECT -> throw new IllegalStateException("adapter feature accessor mismatch");
            case ACCESS_POLICY_DEFECT -> throw new IllegalStateException("adapter feature access policy violation");
            case VALID -> throw new IllegalArgumentException("status is not a degraded feature status");
        };
        return new FraudSignalEvaluation(
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
                null,
                null,
                reasonCode.wireValue()
        );
    }

    private FraudSignalEvaluation availableResult(FraudScoreResult productionResult) {
        List<String> reasonCodes = ReasonCode.supportedWireValues(
                ReasonCode.parseInputList(productionResult.reasonCodes())
        );
        return new FraudSignalEvaluation(
                FraudEngineStatus.AVAILABLE,
                productionResult.fraudScore(),
                productionResult.riskLevel(),
                FraudEngineConfidence.UNKNOWN,
                reasonCodes,
                contributionsFor(reasonCodes),
                evidenceFor(reasonCodes),
                productionResult.modelName(),
                productionResult.modelVersion(),
                null
        );
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
