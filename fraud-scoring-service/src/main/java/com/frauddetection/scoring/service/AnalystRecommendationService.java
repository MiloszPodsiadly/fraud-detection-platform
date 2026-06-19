package com.frauddetection.scoring.service;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.events.recommendation.AnalystRecommendation;
import com.frauddetection.common.events.recommendation.AnalystRecommendationConfidence;
import com.frauddetection.common.events.recommendation.AnalystRecommendationNonDecisioning;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import com.frauddetection.common.events.recommendation.AnalystRecommendationSource;
import com.frauddetection.common.events.recommendation.AnalystRecommendationStatus;
import com.frauddetection.common.events.recommendation.AnalystRecommendationWarning;
import com.frauddetection.scoring.domain.FraudScoreResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
public class AnalystRecommendationService {

    private static final List<String> RAPID_TRANSFER_CODES = List.of(
            ReasonCode.RAPID_PLN_20K_BURST.wireValue(),
            ReasonCode.RAPID_TRANSFER_FRAUD_CASE.wireValue(),
            "RAPID_TRANSFER_BURST_SIGNAL",
            "RAPID_TRANSFER_PATTERN_MATCHED"
    );

    public AnalystRecommendationResult recommend(
            FraudScoreResult scoreResult,
            Optional<EngineIntelligenceSummary> engineIntelligence
    ) {
        if (engineIntelligence.isEmpty()) {
            return AnalystRecommendationResult.absent();
        }

        EngineIntelligenceSummary summary = engineIntelligence.get();
        if (summary.engines().isEmpty()) {
            return AnalystRecommendationResult.insufficientData("ENGINE_INTELLIGENCE_NO_ENGINES");
        }

        Optional<EngineIntelligenceEngineResult> rules = engine(summary, FraudEngineType.RULES);
        Optional<EngineIntelligenceEngineResult> ml = engine(summary, FraudEngineType.ML_MODEL);
        if (rules.isEmpty() && ml.isEmpty()) {
            return AnalystRecommendationResult.notApplicable("ENGINE_INTELLIGENCE_NO_COMPARABLE_ENGINES");
        }

        boolean degraded = degraded(summary);
        Optional<RecommendationDecision> decision = rapidTransferRecommendation(rules, degraded)
                .or(() -> rulesHighRiskRecommendation(rules, degraded))
                .or(() -> mlHighRulesLowerRecommendation(rules, ml, degraded))
                .or(() -> bothLowRecommendation(rules, ml, degraded));

        return decision
                .map(RecommendationDecision::toResult)
                .orElseGet(() -> AnalystRecommendationResult.insufficientData("ANALYST_RECOMMENDATION_NO_RULE_MATCH"));
    }

    public AnalystRecommendationResult unavailable() {
        return AnalystRecommendationResult.unavailable();
    }

    private Optional<RecommendationDecision> rapidTransferRecommendation(
            Optional<EngineIntelligenceEngineResult> rules,
            boolean degraded
    ) {
        if (rules.isEmpty() || !hasHighRisk(rules.get()) || !hasAnyReasonCode(rules.get(), RAPID_TRANSFER_CODES)) {
            return Optional.empty();
        }
        return Optional.of(new RecommendationDecision(
                status(degraded),
                AnalystRecommendation.RECOMMEND_CASE_CREATION,
                AnalystRecommendationConfidence.LOW,
                source(degraded, AnalystRecommendationSource.RULES_RISK),
                List.of("RAPID_TRANSFER_PATTERN", "RULES_HIGH_RISK"),
                warnings(degraded)
        ));
    }

    private Optional<RecommendationDecision> rulesHighRiskRecommendation(
            Optional<EngineIntelligenceEngineResult> rules,
            boolean degraded
    ) {
        if (rules.isEmpty() || !hasHighRisk(rules.get())) {
            return Optional.empty();
        }
        return Optional.of(new RecommendationDecision(
                status(degraded),
                AnalystRecommendation.RECOMMEND_REVIEW,
                confidenceFor(rules.get().riskLevel()),
                source(degraded, AnalystRecommendationSource.RULES_RISK),
                List.of(ruleRiskReason(rules.get().riskLevel())),
                warnings(degraded)
        ));
    }

    private Optional<RecommendationDecision> mlHighRulesLowerRecommendation(
            Optional<EngineIntelligenceEngineResult> rules,
            Optional<EngineIntelligenceEngineResult> ml,
            boolean degraded
    ) {
        if (rules.isEmpty() || ml.isEmpty() || !hasHighRisk(ml.get()) || !hasLowOrMediumRisk(rules.get())) {
            return Optional.empty();
        }
        return Optional.of(new RecommendationDecision(
                status(degraded),
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationConfidence.LOW,
                source(degraded, AnalystRecommendationSource.RISK_MISMATCH),
                List.of("ML_HIGH_RISK_RULES_LOWER_RISK", "ENGINE_RISK_DISAGREEMENT"),
                warnings(degraded)
        ));
    }

    private Optional<RecommendationDecision> bothLowRecommendation(
            Optional<EngineIntelligenceEngineResult> rules,
            Optional<EngineIntelligenceEngineResult> ml,
            boolean degraded
    ) {
        if (rules.isEmpty() || ml.isEmpty() || !hasLowRisk(rules.get()) || !hasLowRisk(ml.get())) {
            return Optional.empty();
        }
        return Optional.of(new RecommendationDecision(
                status(degraded),
                AnalystRecommendation.RECOMMEND_NO_ACTION,
                AnalystRecommendationConfidence.LOW,
                source(degraded, AnalystRecommendationSource.ENGINE_COMPARISON),
                List.of("BOTH_ENGINES_LOW_RISK", "LOW_RISK_DIAGNOSTIC_CONTEXT"),
                warnings(degraded)
        ));
    }

    private Optional<EngineIntelligenceEngineResult> engine(
            EngineIntelligenceSummary summary,
            FraudEngineType engineType
    ) {
        return summary.engines().stream()
                .filter(engine -> engine.engineType() == engineType)
                .findFirst();
    }

    private boolean degraded(EngineIntelligenceSummary summary) {
        return !summary.warnings().isEmpty()
                || summary.engines().stream().anyMatch(engine -> engine.status() != FraudEngineStatus.AVAILABLE);
    }

    private boolean hasHighRisk(EngineIntelligenceEngineResult engine) {
        return engine.status() == FraudEngineStatus.AVAILABLE
                && (engine.riskLevel() == RiskLevel.HIGH || engine.riskLevel() == RiskLevel.CRITICAL);
    }

    private boolean hasLowOrMediumRisk(EngineIntelligenceEngineResult engine) {
        return engine.status() == FraudEngineStatus.AVAILABLE
                && (engine.riskLevel() == RiskLevel.LOW || engine.riskLevel() == RiskLevel.MEDIUM);
    }

    private boolean hasLowRisk(EngineIntelligenceEngineResult engine) {
        return engine.status() == FraudEngineStatus.AVAILABLE && engine.riskLevel() == RiskLevel.LOW;
    }

    private boolean hasAnyReasonCode(EngineIntelligenceEngineResult engine, List<String> reasonCodes) {
        return engine.reasonCodes().stream().anyMatch(reasonCodes::contains);
    }

    private AnalystRecommendationStatus status(boolean degraded) {
        return degraded ? AnalystRecommendationStatus.DEGRADED : AnalystRecommendationStatus.AVAILABLE;
    }

    private AnalystRecommendationSource source(boolean degraded, AnalystRecommendationSource source) {
        return degraded ? AnalystRecommendationSource.ENGINE_INTELLIGENCE_DEGRADED : source;
    }

    private AnalystRecommendationConfidence confidenceFor(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.CRITICAL
                ? AnalystRecommendationConfidence.MEDIUM
                : AnalystRecommendationConfidence.LOW;
    }

    private String ruleRiskReason(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.CRITICAL ? "RULES_CRITICAL_RISK" : "RULES_HIGH_RISK";
    }

    private List<AnalystRecommendationWarning> warnings(boolean degraded) {
        return degraded ? List.of(new AnalystRecommendationWarning("ENGINE_INTELLIGENCE_DEGRADED", 1)) : List.of();
    }

    private record RecommendationDecision(
            AnalystRecommendationStatus status,
            AnalystRecommendation recommendation,
            AnalystRecommendationConfidence confidence,
            AnalystRecommendationSource source,
            List<String> reasonCodes,
            List<AnalystRecommendationWarning> warnings
    ) {
        private AnalystRecommendationResult toResult() {
            return new AnalystRecommendationResult(
                    status,
                    recommendation,
                    confidence,
                    source,
                    List.copyOf(new LinkedHashSet<>(reasonCodes)),
                    warnings,
                    AnalystRecommendationNonDecisioning.advisoryOnly()
            );
        }
    }
}
