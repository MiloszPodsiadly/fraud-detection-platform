package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.api.EngineIntelligenceComparisonResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceOpenApiContractTest {
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void openApiDocumentIsAcceptedByStandardsCompatibleParser() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);

        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(openApiPath().toString(), null, options);

        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getPaths())
                .containsKey("/api/v1/transactions/scored/{transactionId}/engine-intelligence");
        assertThat(result.getOpenAPI().getComponents().getSchemas())
                .containsKeys(
                        "EngineIntelligenceResponse",
                        "EngineIntelligenceEngineResponse",
                        "EngineIntelligenceDiagnosticSignalResponse"
                );
    }

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
                "comparisonType:",
                "comparedEngineIds:",
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
                "- analystRecommendation",
                "engineIntelligence:",
                "$ref: \"#/components/schemas/EngineIntelligenceResponse\"",
                "analystRecommendation:",
                "$ref: \"#/components/schemas/AnalystRecommendationResult\""
        );
    }

    @Test
    void openApiListSchemaIsLightweightAndDoesNotRequireEngineIntelligence() throws Exception {
        assertThat(scoredTransactionListPath()).contains("$ref: \"#/components/schemas/PagedScoredTransactionResponse\"");
        assertThat(pagedScoredTransactionSchema()).contains(
                "Lightweight scored transaction list page",
                "$ref: \"#/components/schemas/ScoredTransactionResponse\""
        );
        assertThat(scoredTransactionSchema()).contains("Engine intelligence diagnostics and analyst recommendation details are intentionally omitted")
                .contains("analyst recommendation details are intentionally omitted")
                .doesNotContain("engineIntelligence:", "analystRecommendation:");
    }

    @Test
    void openApiScoredTransactionDetailContainsAdvisoryAnalystRecommendationContract() throws Exception {
        String schema = analystRecommendationSchema();

        assertThat(schema).contains(
                "AnalystRecommendationResult:",
                "Advisory-only analyst recommendation generated by fraud-scoring-service",
                "required: [status, recommendation, recommendationVersion, generatedAt, confidence, source, reasonCodes, warnings, nonDecisioning]",
                "enum: [AVAILABLE, ABSENT, NOT_APPLICABLE, INSUFFICIENT_DATA, UNAVAILABLE, DEGRADED]",
                "enum: [RECOMMEND_REVIEW, RECOMMEND_CASE_CREATION, RECOMMEND_STEP_UP_REVIEW, RECOMMEND_MONITOR, RECOMMEND_NO_ACTION]",
                "recommendationVersion:",
                "example: analyst-recommendation-v1",
                "generatedAt:",
                "format: date-time",
                "nullable: true",
                "enum: [UNKNOWN, LOW, MEDIUM]",
                "enum: [RULES_RISK, ENGINE_COMPARISON, RISK_MISMATCH, ENGINE_INTELLIGENCE_ABSENT, ENGINE_INTELLIGENCE_DEGRADED, ENGINE_INTELLIGENCE_UNAVAILABLE, NOT_APPLICABLE]",
                "maxItems: 5",
                "maxItems: 10",
                "AnalystRecommendationNonDecisioning:",
                "notPaymentAuthorization:",
                "notAutomaticDecisioning:",
                "notCaseAction:",
                "notWorkflowAction:",
                "notModelPromotion:",
                "notThresholdRecommendation:"
        ).doesNotContain("nullable: true\n          enum: [RULES_RISK");
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
                "comparisonType:",
                "comparedEngineIds:",
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
    void openApiBindsEngineIdsToExactEngineTypes() throws Exception {
        String openApi = openApi();

        assertThat(openApi).contains(
                "RulesEngineIdentity:",
                "enum: [rules.primary]",
                "enum: [RULES]",
                "MlEngineIdentity:",
                "enum: [ml.python.primary]",
                "enum: [ML_MODEL]",
                "VelocityEngineIdentity:",
                "enum: [velocity.primary]",
                "enum: [VELOCITY]"
        );
        assertThat(schema("EngineIntelligenceEngineResponse")).contains(
                "oneOf:",
                "$ref: \"#/components/schemas/RulesEngineIdentity\"",
                "$ref: \"#/components/schemas/MlEngineIdentity\"",
                "$ref: \"#/components/schemas/VelocityEngineIdentity\""
        );
        assertThat(schema("EngineIntelligenceDiagnosticSignalResponse")).contains("oneOf:");
        assertThat(schema("EngineIntelligenceEngineReadModel")).contains("oneOf:");
        assertThat(schema("EngineIntelligenceDiagnosticSignalReadModel")).contains("oneOf:");
    }

    @Test
    void parsedOpenApiContractMatchesRuntimeEngineIdentityContract() throws Exception {
        Map<String, Object> schemas = schemas();
        JsonNode registry = objectMapper.readTree(engineRegistryFixturePath().toFile());
        Map<String, Object> response = schema(schemas, "EngineIntelligenceResponse");

        assertThat(registry.get("maxEngineCount").intValue())
                .isEqualTo(FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_ENGINES);
        assertThat(StreamSupport.stream(registry.get("order").spliterator(), false)
                .map(JsonNode::textValue)
                .toList())
                .containsExactlyElementsOf(FraudEngineIdentityContract.engineOrder());
        assertThat(enumValues(property(response, "contractVersion")))
                .containsExactly(EngineIntelligenceSummaryContract.VERSION);
        assertThat(property(response, "engines"))
                .containsEntry("maxItems", FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_ENGINES);
        Map<String, Object> comparison = schema(schemas, "EngineIntelligenceComparisonResponse");
        assertThat(list(comparison, "required")).containsExactly(
                "comparisonType",
                "comparedEngineIds",
                "agreementStatus",
                "riskMismatchStatus",
                "scoreDeltaBucket"
        );
        assertThat(enumValues(property(comparison, "comparisonType"))).containsExactly("RULES_VS_ML");
        assertThat(registry.get("comparison").get("comparisonType").textValue()).isEqualTo("RULES_VS_ML");
        assertThat(property(comparison, "comparedEngineIds"))
                .containsEntry("minItems", 2)
                .containsEntry("maxItems", 2)
                .containsEntry("uniqueItems", true);
        assertThat(registry.get("comparison").get("comparedEngineIds"))
                .hasSize(FraudEngineIdentityContract.rulesVsMlComparisonEngineIds().size());
        assertThat(oneOfRefs(schema(schemas, "EngineIntelligenceEngineResponse")))
                .containsExactly(
                        "#/components/schemas/RulesEngineIdentity",
                        "#/components/schemas/MlEngineIdentity",
                        "#/components/schemas/VelocityEngineIdentity"
                );
        assertThat(oneOfRefs(schema(schemas, "EngineIntelligenceDiagnosticSignalResponse")))
                .containsExactly(
                        "#/components/schemas/RulesEngineIdentity",
                        "#/components/schemas/MlEngineIdentity",
                        "#/components/schemas/VelocityEngineIdentity"
                );
        assertIdentitySchema(
                schemas,
                "RulesEngineIdentity",
                FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                FraudEngineType.RULES
        );
        assertIdentitySchema(
                schemas,
                "MlEngineIdentity",
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                FraudEngineType.ML_MODEL
        );
        assertIdentitySchema(
                schemas,
                "VelocityEngineIdentity",
                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                FraudEngineType.VELOCITY
        );
    }

    @Test
    void serializedRuntimeComparisonInstanceMatchesParsedOpenApiSchema() throws Exception {
        EngineIntelligenceResponse response = new EngineIntelligenceResponse(
                EngineIntelligenceResponseStatus.AVAILABLE,
                com.frauddetection.common.events.intelligence.EngineIntelligenceSummary.CONTRACT_VERSION,
                Instant.parse("2026-06-01T06:00:00Z"),
                new EngineIntelligenceComparisonResponse(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        FraudEngineIdentityContract.rulesVsMlComparisonEngineIds(),
                        EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        EngineIntelligenceScoreDeltaBucket.LARGE
                ),
                List.of(),
                List.of(),
                List.of()
        );
        Map<String, Object> serialized = map(new Yaml().load(objectMapper.writeValueAsString(response)));
        Map<String, Object> comparison = map(serialized, "comparison");
        Map<String, Object> comparisonSchema = schema(schemas(), "EngineIntelligenceComparisonResponse");

        assertThat(comparison.keySet()).containsExactlyElementsOf(map(comparisonSchema, "properties").keySet());
        assertThat(comparison.get("comparisonType")).isEqualTo("RULES_VS_ML");
        assertThat(comparison.get("comparedEngineIds"))
                .isEqualTo(FraudEngineIdentityContract.rulesVsMlComparisonEngineIds());
        assertThat(enumValues(property(comparisonSchema, "comparisonType")))
                .contains(comparison.get("comparisonType"));
        assertThat(enumValues(property(comparisonSchema, "agreementStatus")))
                .contains(comparison.get("agreementStatus"));
        assertThat(enumValues(property(comparisonSchema, "riskMismatchStatus")))
                .contains(comparison.get("riskMismatchStatus"));
        assertThat(enumValues(property(comparisonSchema, "scoreDeltaBucket")))
                .contains(comparison.get("scoreDeltaBucket"));
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

    private String analystRecommendationSchema() throws Exception {
        String openApi = openApi();
        int schemaStart = openApi.indexOf("    AnalystRecommendationResult:");
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

    private Path engineRegistryFixturePath() {
        Path fromRoot = Path.of(
                "common-events",
                "src/test/resources/fixtures/engine-intelligence/engine_registry_contract.json"
        );
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of(
                "..",
                "common-events",
                "src/test/resources/fixtures/engine-intelligence/engine_registry_contract.json"
        );
    }

    private Map<String, Object> schemas() throws Exception {
        return map(map(new Yaml().load(openApi()), "components"), "schemas");
    }

    private Map<String, Object> schema(Map<String, Object> schemas, String name) {
        return map(schemas, name);
    }

    private Map<String, Object> property(Map<String, Object> schema, String name) {
        return map(map(schema, "properties"), name);
    }

    private List<Object> enumValues(Map<String, Object> property) {
        return list(property, "enum");
    }

    private List<String> oneOfRefs(Map<String, Object> schema) {
        return list(schema, "oneOf").stream()
                .map(item -> map(item).get("$ref").toString())
                .toList();
    }

    private void assertIdentitySchema(
            Map<String, Object> schemas,
            String schemaName,
            String engineId,
            FraudEngineType engineType
    ) {
        Map<String, Object> identitySchema = schema(schemas, schemaName);

        assertThat(list(identitySchema, "required")).containsExactly("engineId", "engineType");
        assertThat(enumValues(property(identitySchema, "engineId"))).containsExactly(engineId);
        assertThat(enumValues(property(identitySchema, "engineType"))).containsExactly(engineType.name());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object source) {
        return (Map<String, Object>) source;
    }

    private Map<String, Object> map(Map<String, Object> source, String key) {
        return map(source.get(key));
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Map<String, Object> source, String key) {
        return (List<Object>) source.get(key);
    }

    private static final class EngineIntelligenceSummaryContract {
        private static final int VERSION = com.frauddetection.common.events.intelligence.EngineIntelligenceSummary.CONTRACT_VERSION;
    }
}
