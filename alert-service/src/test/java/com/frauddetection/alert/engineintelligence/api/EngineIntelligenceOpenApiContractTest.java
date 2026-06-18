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
    void openApiContainsScoredTransactionDetailWithBoundedEngineIntelligence() throws Exception {
        assertThat(openApi()).contains("/api/v1/transactions/scored/{transactionId}:");
        assertThat(scoredTransactionDetailPath()).contains(
                "summary: Read one scored transaction with bounded engine intelligence",
                "pattern: \"^[A-Za-z0-9._:-]+$\"",
                "$ref: \"#/components/schemas/ScoredTransactionDetailResponse\"",
                "\"400\":",
                "\"401\":",
                "\"403\":",
                "\"404\":"
        );
        assertThat(scoredTransactionSchema()).contains(
                "transactionId:",
                "fraudScore:",
                "riskLevel:",
                "alertRecommended:",
                "reasonCodes:"
        ).doesNotContain("engineIntelligence:");
        assertThat(scoredTransactionDetailSchema()).contains(
                "required:",
                "- engineIntelligence",
                "engineIntelligence:",
                "$ref: \"#/components/schemas/EngineIntelligenceResponse\""
        );
    }

    @Test
    void openApiListSchemaIsLightweightAndDoesNotRequireEngineIntelligence() throws Exception {
        assertThat(scoredTransactionListPath()).contains("$ref: \"#/components/schemas/PagedScoredTransactionResponse\"");
        assertThat(pagedScoredTransactionSchema()).contains(
                "Lightweight scored transaction list page",
                "$ref: \"#/components/schemas/ScoredTransactionResponse\""
        );
        assertThat(scoredTransactionSchema()).contains("Engine intelligence diagnostics are intentionally omitted")
                .doesNotContain("engineIntelligence:");
    }

    @Test
    void openApiScoredTransactionEngineIntelligenceHasExplicitAbsenceAndDegradationStatuses() throws Exception {
        String schema = publicEngineIntelligenceSchema();

        assertThat(schema).contains(
                "EngineIntelligenceResponse:",
                "enum: [AVAILABLE, ABSENT, UNAVAILABLE, DEGRADED]",
                "- contractVersion",
                "- generatedAt",
                "- comparison",
                "ABSENT means no projection exists",
                "UNAVAILABLE means the projection read path degraded",
                "explicit null contractVersion, generatedAt, and comparison fields",
                "EngineIntelligenceEngineResponse:",
                "enum: [AVAILABLE, UNAVAILABLE, TIMEOUT, DEGRADED, NOT_APPLICABLE]",
                "EngineIntelligenceDiagnosticSignalResponse:",
                "EngineIntelligenceWarningResponse:"
        );
    }

    @Test
    void openApiScoredTransactionEngineIntelligenceDocumentsAnalystOnlyBoundary() throws Exception {
        assertThat(scoredTransactionDetailPath()).contains(
                "analyst intelligence read only",
                "does not recompute scoring",
                "payment authorization",
                "automatic approve",
                "automatic decline",
                "automatic block",
                "model promotion",
                "threshold recommendation",
                "workflow execution",
                "final bank authorization"
        );
    }

    @Test
    void openApiScoredTransactionEngineIntelligenceDoesNotExposeRawInternalFields() throws Exception {
        assertThat(scoredTransactionSchema() + publicEngineIntelligenceSchema()).doesNotContain(
                "_id",
                "FraudEngineResult",
                "NormalizedFraudEngineResult",
                "rawEvidence",
                "rawContribution",
                "featureSnapshot",
                "featureVector",
                "rawPayload",
                "rawMlRequest",
                "rawMlResponse",
                "payloadHash",
                "endpoint",
                "token",
                "secret",
                "stacktrace",
                "exceptionMessage",
                "internalAggregation",
                "ScoringContext",
                "finalDecision",
                "recommendedAction",
                "approveTransaction",
                "declineTransaction",
                "blockTransaction",
                "paymentAuthorization",
                "winningEngine",
                "platformRiskScore"
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
        int schemaEnd = openApi.indexOf("\n    ShadowPerformance", schemaStart);
        if (schemaEnd < 0) {
            schemaEnd = openApi.indexOf("\n  responses:", schemaStart);
        }
        return openApi.substring(
                schemaStart,
                schemaEnd
        );
    }

    private String engineIntelligencePath() throws Exception {
        String openApi = openApi();
        int pathStart = openApi.indexOf("  /api/v1/transactions/scored/{transactionId}/engine-intelligence:");
        return openApi.substring(pathStart, openApi.indexOf("\n  /", pathStart + 1));
    }

    private String scoredTransactionDetailPath() throws Exception {
        String openApi = openApi();
        int pathStart = openApi.indexOf("  /api/v1/transactions/scored/{transactionId}:");
        return openApi.substring(pathStart, openApi.indexOf("\n  /", pathStart + 1));
    }

    private String scoredTransactionListPath() throws Exception {
        String openApi = openApi();
        int pathStart = openApi.indexOf("  /api/v1/transactions/scored:");
        return openApi.substring(pathStart, openApi.indexOf("\n  /", pathStart + 1));
    }

    private String scoredTransactionSchema() throws Exception {
        String openApi = openApi();
        int schemaStart = openApi.indexOf("    ScoredTransactionResponse:");
        return openApi.substring(schemaStart, openApi.indexOf("\n    ScoredTransactionDetailResponse:", schemaStart));
    }

    private String scoredTransactionDetailSchema() throws Exception {
        String openApi = openApi();
        int schemaStart = openApi.indexOf("    ScoredTransactionDetailResponse:");
        return openApi.substring(schemaStart, openApi.indexOf("\n    AuditEventReadResponse:", schemaStart));
    }

    private String pagedScoredTransactionSchema() throws Exception {
        String openApi = openApi();
        int schemaStart = openApi.indexOf("    PagedScoredTransactionResponse:");
        return openApi.substring(schemaStart, openApi.indexOf("\n    MoneyResponse:", schemaStart));
    }

    private String publicEngineIntelligenceSchema() throws Exception {
        String openApi = openApi();
        int schemaStart = openApi.indexOf("    EngineIntelligenceResponse:");
        return openApi.substring(schemaStart, openApi.indexOf("\n    EngineIntelligenceReadModel:", schemaStart));
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
