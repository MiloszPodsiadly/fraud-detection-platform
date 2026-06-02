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

    @Test
    void openApiPathParameterHasBoundedTransactionIdSchema() throws Exception {
        assertThat(engineIntelligencePath()).contains(
                "name: transactionId",
                "in: path",
                "minLength: 1",
                "maxLength: 128",
                "pattern: \"^[A-Za-z0-9._:-]+$\"",
                "\"503\":",
                "$ref: \"#/components/responses/ServiceUnavailable\""
        );
    }

    @Test
    void openApiResponseTransactionIdHasMaxLength() throws Exception {
        assertThat(schema("EngineIntelligenceReadModel")).contains(
                "transactionId:",
                "minLength: 1",
                "maxLength: 128"
        );
    }

    @Test
    void openApiEngineIdHasMaxLength() throws Exception {
        assertThat(schema("EngineIntelligenceEngineReadModel")).contains(
                "engineId:",
                "minLength: 1",
                "maxLength: 128"
        );
        assertThat(schema("EngineIntelligenceDiagnosticSignalReadModel")).contains(
                "engineId:",
                "minLength: 1",
                "maxLength: 128"
        );
    }

    @Test
    void openApiReasonCodesHaveMaxLength() throws Exception {
        assertThat(schema("EngineIntelligenceEngineReadModel")).contains(
                "reasonCodes:",
                "minLength: 1",
                "maxLength: 128"
        );
        assertThat(schema("EngineIntelligenceDiagnosticSignalReadModel")).contains(
                "reasonCode:",
                "minLength: 1",
                "maxLength: 128"
        );
    }

    @Test
    void openApiWarningCodeHasMaxLength() throws Exception {
        assertThat(schema("EngineIntelligenceWarningReadModel")).contains(
                "warningCode:",
                "minLength: 1",
                "maxLength: 128"
        );
    }

    private String engineIntelligenceSchema() throws Exception {
        String openApi = openApi();
        int schemaStart = openApi.indexOf("    EngineIntelligenceReadModel:");
        return openApi.substring(
                schemaStart,
                openApi.indexOf("  responses:", schemaStart)
        );
    }

    private String engineIntelligencePath() throws Exception {
        String openApi = openApi();
        int pathStart = openApi.indexOf("  /api/v1/transactions/scored/{transactionId}/engine-intelligence:");
        return openApi.substring(pathStart, openApi.indexOf("\n  /", pathStart + 1));
    }

    private String schema(String name) throws Exception {
        String openApi = openApi();
        int schemaStart = openApi.indexOf("    " + name + ":");
        int nextSchema = openApi.indexOf("\n    EngineIntelligence", schemaStart + 1);
        if (nextSchema < 0) {
            nextSchema = openApi.indexOf("\n  responses:", schemaStart + 1);
        }
        return openApi.substring(schemaStart, nextSchema);
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
