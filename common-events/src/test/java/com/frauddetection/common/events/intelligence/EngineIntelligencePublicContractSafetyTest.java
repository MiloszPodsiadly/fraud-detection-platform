package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligencePublicContractSafetyTest {
    private static final List<Class<?>> PUBLIC_DTOS = List.of(
            EngineIntelligenceSummary.class,
            EngineIntelligenceEngineResult.class,
            EngineIntelligenceComparison.class,
            EngineIntelligenceDiagnosticSignal.class,
            EngineIntelligenceWarningSummary.class
    );
    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "rawScore", "score", "rawEvidence", "evidenceDescription", "evidenceTitle", "rawContribution",
            "contributionValue", "featureSnapshot", "featureVector", "rawPayload", "payload", "endpoint", "host",
            "token", "secret", "apiKey", "stackTrace", "exception", "debug", "finalDecision", "recommendedAction",
            "winningEngine", "approve", "decline", "block", "authorizationDecision", "paymentDecision", "finalRisk",
            "finalScore", "platformRiskScore", "platformRiskLevel", "internalAggregationResult",
            "normalizedEngineResult", "strongestSignalInternal"
    );

    @Test
    void publicDtosUseExactFieldAllowlists() {
        assertFields(EngineIntelligenceSummary.class,
                "contractVersion", "generatedAt", "engines", "comparison", "diagnosticSignals", "warnings");
        assertFields(EngineIntelligenceEngineResult.class,
                "engineId", "engineType", "status", "riskLevel", "scoreBucket", "reasonCodes");
        assertFields(EngineIntelligenceComparison.class,
                "agreementStatus", "riskMismatchStatus", "scoreDeltaBucket");
        assertFields(EngineIntelligenceDiagnosticSignal.class,
                "engineId", "engineType", "engineStatus", "signalCategory", "riskLevel", "scoreBucket", "reasonCode");
        assertFields(EngineIntelligenceWarningSummary.class, "code", "count");
    }

    @Test
    void publicDtosContainNoForbiddenFields() {
        assertThat(PUBLIC_DTOS.stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getName))
                .doesNotContainAnyElementsOf(FORBIDDEN_FIELDS);
    }

    @Test
    void serializedJsonContainsNoForbiddenFieldNames() throws Exception {
        String json = EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.summary());

        FORBIDDEN_FIELDS.forEach(field -> assertThat(json).doesNotContain("\"" + field + "\":"));
    }

    @Test
    void publicDtosDoNotReferenceAggregationPackage() {
        assertThat(PUBLIC_DTOS.stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(component -> component.getGenericType().getTypeName()))
                .noneMatch(type -> type.contains("com.frauddetection.scoring.orchestration.aggregation"));
    }

    private void assertFields(Class<?> type, String... fields) {
        assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)).containsExactly(fields);
    }
}
