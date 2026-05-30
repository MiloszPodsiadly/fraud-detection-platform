package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class FraudScoringOrchestratorTestSupport {
    static final Instant RECEIVED_AT = Instant.parse("2026-05-30T10:00:00Z");

    private FraudScoringOrchestratorTestSupport() {
    }

    static ScoringContext context() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(
                event,
                event.featureSnapshot(),
                ScoringMode.ML,
                event.correlationId(),
                RECEIVED_AT
        );
    }

    static FakeFraudSignalEngine ruleEngine(FraudEngineResult result) {
        return new FakeFraudSignalEngine(ruleDescriptor(), ignored -> result);
    }

    static FakeFraudSignalEngine mlEngine(FraudEngineResult result) {
        return new FakeFraudSignalEngine(mlDescriptor(), ignored -> result);
    }

    static FakeFraudSignalEngine throwingRuleEngine(RuntimeException exception) {
        return new FakeFraudSignalEngine(ruleDescriptor(), ignored -> {
            throw exception;
        });
    }

    static FakeFraudSignalEngine throwingMlEngine(RuntimeException exception) {
        return new FakeFraudSignalEngine(mlDescriptor(), ignored -> {
            throw exception;
        });
    }

    static FraudEngineDescriptor ruleDescriptor() {
        return new FraudEngineDescriptor(
                FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID,
                FraudEngineType.RULES,
                "java",
                "1.0.0",
                true
        );
    }

    static FraudEngineDescriptor mlDescriptor() {
        return new FraudEngineDescriptor(
                FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID,
                FraudEngineType.ML_MODEL,
                "python",
                "1.0.0",
                false
        );
    }

    static FraudEngineResult availableResult(FraudEngineDescriptor descriptor, double score, RiskLevel riskLevel) {
        return result(
                descriptor,
                FraudEngineStatus.AVAILABLE,
                score,
                riskLevel,
                FraudEngineConfidence.MEDIUM,
                "ENGINE_SIGNAL",
                null
        );
    }

    static FraudEngineResult unavailableResult(FraudEngineDescriptor descriptor) {
        return result(
                descriptor,
                FraudEngineStatus.UNAVAILABLE,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                "ENGINE_UNAVAILABLE",
                "ENGINE_UNAVAILABLE"
        );
    }

    static FraudEngineResult timeoutResult(FraudEngineDescriptor descriptor) {
        return result(
                descriptor,
                FraudEngineStatus.TIMEOUT,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                "ENGINE_TIMEOUT",
                "ENGINE_TIMEOUT"
        );
    }

    static FraudEngineResult degradedResult(FraudEngineDescriptor descriptor) {
        return result(
                descriptor,
                FraudEngineStatus.DEGRADED,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                "ENGINE_DEGRADED",
                "ENGINE_DEGRADED"
        );
    }

    static String flatten(FraudScoringOrchestrationResult result) {
        return result.engineResults() + " " + result.executionWarnings() + " " + result.generatedAt();
    }

    static List<String> engineIds(FraudScoringOrchestrationResult result) {
        return result.engineResults().stream()
                .map(FraudEngineResult::engineId)
                .toList();
    }

    private static FraudEngineResult result(
            FraudEngineDescriptor descriptor,
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            String reasonCode,
            String statusReason
    ) {
        return new FraudEngineResult(
                descriptor.engineId(),
                descriptor.engineType(),
                descriptor.engineLanguage(),
                status,
                score,
                riskLevel,
                confidence,
                List.of(reasonCode),
                List.of(),
                List.of(),
                0L,
                null,
                null,
                statusReason,
                RECEIVED_AT
        );
    }

    static final class FakeFraudSignalEngine implements FraudSignalEngine {
        private final FraudEngineDescriptor descriptor;
        private final Function<ScoringContext, FraudEngineResult> handler;
        private final List<String> calls;

        FakeFraudSignalEngine(FraudEngineDescriptor descriptor, Function<ScoringContext, FraudEngineResult> handler) {
            this(descriptor, handler, new ArrayList<>());
        }

        FakeFraudSignalEngine(
                FraudEngineDescriptor descriptor,
                Function<ScoringContext, FraudEngineResult> handler,
                List<String> calls
        ) {
            this.descriptor = descriptor;
            this.handler = handler;
            this.calls = calls;
        }

        @Override
        public FraudEngineResult evaluate(ScoringContext context) {
            calls.add(descriptor.engineId());
            return handler.apply(context);
        }

        @Override
        public FraudEngineDescriptor descriptor() {
            return descriptor;
        }

        List<String> calls() {
            return List.copyOf(calls);
        }
    }
}
