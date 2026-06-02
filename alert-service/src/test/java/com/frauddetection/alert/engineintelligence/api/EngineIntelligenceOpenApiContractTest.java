package com.frauddetection.alert.engineintelligence.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceOpenApiContractTest {

    @Test
    void openApiContainsOnlyBoundedEngineIntelligenceSchema() throws Exception {
        String schema = engineIntelligenceSchema();

        assertThat(openApi()).contains("/api/v1/transactions/scored/{transactionId}/engine-intelligence:");
        assertThat(schema).contains(
                "transactionId:",
                "available:",
                "reason:",
                "contractVersion:",
                "generatedAt:",
                "comparison:",
                "agreementStatus:",
                "riskMismatchStatus:",
                "scoreDeltaBucket:",
                "engineCount:",
                "diagnosticSignalCount:",
                "warningCount:",
                "engines:",
                "diagnosticSignals:",
                "warnings:"
        );
    }

    @Test
    void openApiDoesNotContainRawInternalOrDecisioningFields() throws Exception {
        assertThat(engineIntelligenceSchema()).doesNotContain(
                "_id", "rawEvidence", "rawContribution", "featureSnapshot", "featureVector", "rawPayload",
                "payload", "endpoint", "token", "secret", "stacktrace", "exceptionMessage",
                "internalAggregation", "FraudEngineAggregationResult", "NormalizedFraudEngineResult",
                "ScoringContext", "rawMlResponse", "createdAt", "updatedAt", "finalDecision",
                "recommendedAction", "approve", "decline", "block", "paymentAuthorization", "winningEngine",
                "platformRiskScore"
        );
    }

    @Test
    void openApiDoesNotExposeProjectionClassNames() throws Exception {
        assertThat(engineIntelligenceSchema()).doesNotContain("EngineIntelligenceProjection");
    }

    private String engineIntelligenceSchema() throws Exception {
        String openApi = openApi();
        int schemaStart = openApi.indexOf("    EngineIntelligenceReadModel:");
        return openApi.substring(
                schemaStart,
                openApi.indexOf("  responses:", schemaStart)
        );
    }

    private String openApi() throws Exception {
        return Files.readString(openApiPath());
    }

    private Path openApiPath() {
        Path fromRoot = Path.of("docs", "openapi", "alert_service.openapi.yaml");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..", "docs", "openapi", "alert_service.openapi.yaml");
    }
}
