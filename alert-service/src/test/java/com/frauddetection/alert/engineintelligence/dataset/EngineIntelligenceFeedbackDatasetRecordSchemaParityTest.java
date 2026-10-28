package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceReasonCodePolicy;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceFeedbackDatasetRecordSchemaParityTest {

    private static final Path SCHEMA_PATH = repositoryRoot()
            .resolve("docs/schemas/engine_intelligence_feedback_dataset_record.schema.json");
    private static final Path SEMANTIC_MATRIX_PATH = repositoryRoot()
            .resolve("common-events/src/test/resources/fixtures/engine-intelligence/engine_intelligence_semantic_matrix.json");
    private static final Path OWNERSHIP_FIXTURE_PATH = repositoryRoot()
            .resolve("common-events/src/test/resources/fixtures/engine-intelligence/engine_reason_code_ownership_contract.json");
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(SCHEMA_PATH));
    }

    @Test
    void availableAndMissingFixturesPassJavaAndDraft202012Schema() throws Exception {
        assertThat(new Fixture().build().projectionStatus())
                .isEqualTo(EngineIntelligenceFeedbackDatasetProjectionStatus.AVAILABLE);
        assertSchemaValid(payload(new Fixture().build()));

        Fixture missing = Fixture.missing();
        assertThat(missing.build().projectionStatus())
                .isEqualTo(EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING);
        assertSchemaValid(payload(missing.build()));
    }

    @Test
    void unavailableRequiredEngineHasCoherentProfileAndComparison() throws Exception {
        Fixture partial = new Fixture();
        partial.mlEngineStatus = FraudEngineStatus.TIMEOUT;
        partial.mlScoreBucket = EngineIntelligenceScoreBucket.UNAVAILABLE;
        partial.mlRiskLevel = null;
        partial.agreementStatus = EngineIntelligenceAgreementStatus.PARTIAL;
        partial.riskMismatchStatus = EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE;
        partial.scoreDeltaBucket = EngineIntelligenceScoreDeltaBucket.UNAVAILABLE;

        assertThat(partial.build().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.PARTIAL);
        assertSchemaValid(payload(partial.build()));

        Fixture unavailableRules = new Fixture();
        unavailableRules.rulesEngineStatus = FraudEngineStatus.TIMEOUT;
        unavailableRules.rulesScoreBucket = EngineIntelligenceScoreBucket.UNAVAILABLE;
        unavailableRules.rulesRiskLevel = null;
        unavailableRules.agreementStatus = EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE;
        unavailableRules.riskMismatchStatus = EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE;
        unavailableRules.scoreDeltaBucket = EngineIntelligenceScoreDeltaBucket.UNAVAILABLE;
        assertThat(unavailableRules.build().agreementStatus())
                .isEqualTo(EngineIntelligenceAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE);
        assertSchemaValid(payload(unavailableRules.build()));
    }

    @Test
    void sharedSemanticMatrixMatchesDatasetRecordAndDraft202012Schema() throws Exception {
        JsonNode cases = OBJECT_MAPPER.readTree(Files.readString(SEMANTIC_MATRIX_PATH)).get("cases");

        for (JsonNode semanticCase : cases) {
            String caseId = semanticCase.get("caseId").asText();
            JsonNode engineIntelligence = semanticCase.get("engineIntelligence");
            Fixture fixture = datasetFixture(engineIntelligence);
            EngineIntelligenceFeedbackDatasetRecord record = fixture.build();

            assertThat(record.projectionStatus()).as(caseId)
                    .isEqualTo(EngineIntelligenceFeedbackDatasetProjectionStatus.AVAILABLE);
            assertSchemaValid(payload(record));

            EngineIntelligenceAgreementStatus invalidAgreement = EngineIntelligenceAgreementStatus.valueOf(
                    semanticCase.get("invalidAgreementStatus").asText()
            );
            Fixture invalidFixture = datasetFixture(engineIntelligence);
            invalidFixture.agreementStatus = invalidAgreement;
            assertThatThrownBy(invalidFixture::build)
                    .as("%s rejects inconsistent agreement status in Java", caseId)
                    .isInstanceOf(IllegalArgumentException.class);

            Map<String, Object> invalidPayload = payload(record);
            invalidPayload.put("agreementStatus", invalidAgreement.name());
            assertThat(validateSchema(invalidPayload))
                    .as("%s rejects inconsistent agreement status in Draft 2020-12 schema", caseId)
                    .isNotEmpty();
        }
    }

    @Test
    void datasetRecordAndSchemaRejectMlOwnedReasonCodeGroupedUnderRules() throws Exception {
        Fixture invalidOwnership = new Fixture();
        invalidOwnership.reasonCodesByEngine = new LinkedHashMap<>();
        invalidOwnership.reasonCodesByEngine.put("rules.primary", List.of("MODEL_HIGH_RISK"));
        invalidOwnership.reasonCodesByEngine.put("ml.python.primary", List.of("ML_MODEL_SIGNAL"));
        invalidOwnership.reasonCodes = List.of("MODEL_HIGH_RISK", "ML_MODEL_SIGNAL");

        assertThatThrownBy(invalidOwnership::build)
                .hasMessage("reasonCodesByEngine contains an unowned reason code");

        Map<String, Object> invalidPayload = payload(new Fixture().build());
        object(invalidPayload.get("reasonCodesByEngine")).put("rules.primary", List.of("MODEL_HIGH_RISK"));
        invalidPayload.put("reasonCodes", List.of("MODEL_HIGH_RISK", "ML_MODEL_SIGNAL"));
        assertSchemaInvalid(invalidPayload);
    }

    @Test
    void datasetRecordAndSchemaRejectUnknownReasonCode() throws Exception {
        Fixture invalidCode = new Fixture();
        invalidCode.reasonCodesByEngine = new LinkedHashMap<>(Fixture.defaultGroups());
        invalidCode.reasonCodesByEngine.put("rules.primary", List.of("UNKNOWN_REASON_CODE"));
        invalidCode.reasonCodes = List.of("UNKNOWN_REASON_CODE", "ML_MODEL_SIGNAL");
        assertThatThrownBy(invalidCode::build)
                .hasMessage("reasonCodesByEngine contains an unowned reason code");

        Map<String, Object> invalidPayload = payload(new Fixture().build());
        object(invalidPayload.get("reasonCodesByEngine")).put("rules.primary", List.of("UNKNOWN_REASON_CODE"));
        invalidPayload.put("reasonCodes", List.of("UNKNOWN_REASON_CODE", "ML_MODEL_SIGNAL"));
        assertSchemaInvalid(invalidPayload);

        Map<String, Object> invalidFlatReason = payload(new Fixture().build());
        invalidFlatReason.put("reasonCodes", List.of("UNKNOWN_REASON_CODE"));
        assertSchemaInvalid(invalidFlatReason);

        Map<String, Object> invalidDiagnostic = payload(new Fixture().build());
        invalidDiagnostic.put("diagnosticSignals", List.of("UNKNOWN_REASON_CODE"));
        assertSchemaInvalid(invalidDiagnostic);
    }

    @Test
    void standaloneRecordShapeDoesNotProveDiagnosticProvenance() {
        Fixture standalone = new Fixture();
        standalone.diagnosticSignals = List.of("DEVICE_SWITCH_OBSERVED");

        EngineIntelligenceFeedbackDatasetRecord record = standalone.build();

        assertThat(record.reasonCodesByEngine().keySet())
                .containsExactly("rules.primary", "ml.python.primary");
        assertThat(record.diagnosticSignals()).containsExactly("DEVICE_SWITCH_OBSERVED");
    }

    @Test
    void schemaReasonCodeEnumsMatchCanonicalOwnershipFixtureAndJavaPolicy() throws Exception {
        Map<String, Object> ownership = OBJECT_MAPPER.readValue(
                Files.readString(OWNERSHIP_FIXTURE_PATH),
                new TypeReference<LinkedHashMap<String, Object>>() { }
        );
        Set<String> orchestrator = fixtureCodes(ownership, "sharedOrchestratorReasonCodes");
        Set<String> nonDeviceShared = fixtureCodes(ownership, "nonDeviceSharedReasonCodes");
        Set<String> rulesMlShared = fixtureCodes(ownership, "rulesMlSharedReasonCodes");
        Set<String> mlVelocityShared = fixtureCodes(ownership, "mlVelocitySharedReasonCodes");

        Map<String, Set<String>> expectedByEngine = new LinkedHashMap<>();
        expectedByEngine.put(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID, merge(
                nonDeviceShared,
                orchestrator,
                rulesMlShared,
                fixtureCodes(ownership, "rulesOwnedReasonCodes")
        ));
        expectedByEngine.put(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, merge(
                nonDeviceShared,
                orchestrator,
                rulesMlShared,
                mlVelocityShared,
                fixtureCodes(ownership, "mlOwnedReasonCodes")
        ));
        expectedByEngine.put(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID, merge(
                nonDeviceShared,
                orchestrator,
                mlVelocityShared,
                fixtureCodes(ownership, "velocityOwnedReasonCodes")
        ));
        expectedByEngine.put(FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID, merge(
                orchestrator,
                fixtureCodes(ownership, "deviceOwnedReasonCodes")
        ));

        Map<String, Object> schemaDocument = OBJECT_MAPPER.readValue(
                Files.readString(SCHEMA_PATH),
                new TypeReference<LinkedHashMap<String, Object>>() { }
        );
        Map<String, Object> definitions = object(schemaDocument.get("$defs"));
        Map<String, Object> recordProperties = object(schemaDocument.get("properties"));
        Map<String, Object> groupedReasons = object(recordProperties.get("reasonCodesByEngine"));
        Map<String, Object> engineProperties = object(groupedReasons.get("properties"));
        assertThat(engineProperties.keySet()).containsExactlyElementsOf(expectedByEngine.keySet());
        assertThat(groupedReasons.get("additionalProperties")).isEqualTo(false);
        assertThat(groupedReasons.get("maxProperties")).isEqualTo(4);

        Map<String, String> definitionByEngine = new LinkedHashMap<>();
        definitionByEngine.put(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID, "rulesReasonCodes");
        definitionByEngine.put(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, "mlReasonCodes");
        definitionByEngine.put(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID, "velocityReasonCodes");
        definitionByEngine.put(FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID, "deviceReasonCodes");
        for (Map.Entry<String, Set<String>> expected : expectedByEngine.entrySet()) {
            String engineId = expected.getKey();
            String definitionName = definitionByEngine.get(engineId);
            assertThat(object(engineProperties.get(engineId)).get("$ref"))
                    .isEqualTo("#/$defs/" + definitionName);

            Map<String, Object> engineCodeSchema = object(definitions.get(definitionName));
            assertThat(engineCodeSchema.get("maxItems")).isEqualTo(5);
            Map<String, Object> items = object(engineCodeSchema.get("items"));
            Set<String> schemaCodes = Set.copyOf(strings(items.get("enum")));
            assertThat(schemaCodes).containsExactlyInAnyOrderElementsOf(expected.getValue());
            assertThat(expected.getValue()).allSatisfy(code -> assertThat(
                    EngineIntelligenceReasonCodePolicy.isAllowedForEngine(engineId, code)
            ).as("Java ownership policy for %s under %s", code, engineId).isTrue());

            for (String code : expected.getValue()) {
                assertSchemaValid(payload(fixtureWithReasonCode(engineId, code).build()));
            }
        }

        Set<String> aggregateVocabulary = new LinkedHashSet<>();
        expectedByEngine.values().forEach(aggregateVocabulary::addAll);
        List<?> knownCodeAlternatives = (List<?>) object(definitions.get("knownReasonCode")).get("anyOf");
        assertThat(knownCodeAlternatives)
                .extracting(value -> object(value).get("$ref"))
                .containsExactly(
                        "#/$defs/rulesReasonCodes/items",
                        "#/$defs/mlReasonCodes/items",
                        "#/$defs/velocityReasonCodes/items",
                        "#/$defs/deviceReasonCodes/items"
                );
        Set<String> schemaAggregateVocabulary = new LinkedHashSet<>();
        for (Object alternative : knownCodeAlternatives) {
            String reference = String.valueOf(object(alternative).get("$ref"));
            String definitionName = reference.substring("#/$defs/".length(), reference.length() - "/items".length());
            schemaAggregateVocabulary.addAll(strings(object(object(definitions.get(definitionName)).get("items"))
                    .get("enum")));
        }
        assertThat(schemaAggregateVocabulary).containsExactlyInAnyOrderElementsOf(aggregateVocabulary);
        assertThat(aggregateVocabulary).contains("MODEL_HIGH_RISK", "DEVICE_EVIDENCE_UNAVAILABLE");
        for (Map.Entry<String, Set<String>> expected : expectedByEngine.entrySet()) {
            assertThat(expected.getValue()).containsAll(orchestrator);
        }

        assertThat(object(object(recordProperties.get("reasonCodes")).get("items")).get("$ref"))
                .isEqualTo("#/$defs/knownReasonCode");
        assertThat(object(object(recordProperties.get("diagnosticSignals")).get("items")).get("$ref"))
                .isEqualTo("#/$defs/knownReasonCode");

        for (String code : aggregateVocabulary) {
            Fixture fixture = fixtureWithReasonCode(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                    "HIGH_RISK_FLAGS_PRESENT");
            fixture.diagnosticSignals = List.of(code);
            assertSchemaValid(payload(fixture.build()));
        }
    }

    @Test
    void rejectsOrphanDeviceRiskAndUnavailableDeviceWithAvailableState() throws Exception {
        Fixture orphanRisk = new Fixture();
        orphanRisk.deviceRiskLevel = RiskLevel.HIGH;
        assertRejectedByJavaAndSchema(orphanRisk, json -> json.put("deviceRiskLevel", "HIGH"));

        Fixture invalidDevice = new Fixture();
        invalidDevice.deviceEngineStatus = FraudEngineStatus.AVAILABLE;
        invalidDevice.deviceScoreBucket = EngineIntelligenceScoreBucket.HIGH;
        assertRejectedByJavaAndSchema(invalidDevice, json -> {
            json.put("deviceEngineStatus", "AVAILABLE");
            json.put("deviceScoreBucket", "HIGH");
        });
    }

    @Test
    void rejectsProjectionVersionAndMissingProjectionStateViolations() throws Exception {
        Fixture availableWithoutVersion = new Fixture();
        availableWithoutVersion.engineIntelligenceContractVersion = null;
        assertRejectedByJavaAndSchema(
                availableWithoutVersion,
                json -> json.put("engineIntelligenceContractVersion", null)
        );

        Fixture missingWithVersion = Fixture.missing();
        missingWithVersion.engineIntelligenceContractVersion = 2;
        assertRejectedByJavaAndSchema(
                missingWithVersion,
                json -> json.put("engineIntelligenceContractVersion", 2)
        );

        Fixture missingWithDevice = Fixture.missing();
        missingWithDevice.deviceEngineStatus = FraudEngineStatus.DEGRADED;
        missingWithDevice.deviceScoreBucket = EngineIntelligenceScoreBucket.UNAVAILABLE;
        assertRejectedByJavaAndSchema(missingWithDevice, json -> {
            json.put("deviceEngineStatus", "DEGRADED");
            json.put("deviceScoreBucket", "UNAVAILABLE");
        });

        Fixture missingWithComparison = Fixture.missing();
        missingWithComparison.agreementStatus = EngineIntelligenceAgreementStatus.AGREEMENT;
        assertRejectedByJavaAndSchema(
                missingWithComparison,
                json -> json.put("agreementStatus", "AGREEMENT")
        );
    }

    @Test
    void rejectsInvalidEngineProfilesAndComparisonPairs() throws Exception {
        Fixture availableWithNullRisk = new Fixture();
        availableWithNullRisk.mlRiskLevel = null;
        assertRejectedByJavaAndSchema(availableWithNullRisk, json -> json.put("mlRiskLevel", null));

        Fixture operationalWithAvailableScore = new Fixture();
        operationalWithAvailableScore.mlEngineStatus = FraudEngineStatus.UNAVAILABLE;
        operationalWithAvailableScore.mlScoreBucket = EngineIntelligenceScoreBucket.HIGH;
        operationalWithAvailableScore.mlRiskLevel = null;
        operationalWithAvailableScore.agreementStatus = EngineIntelligenceAgreementStatus.PARTIAL;
        operationalWithAvailableScore.riskMismatchStatus = EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE;
        operationalWithAvailableScore.scoreDeltaBucket = EngineIntelligenceScoreDeltaBucket.UNAVAILABLE;
        assertRejectedByJavaAndSchema(operationalWithAvailableScore, json -> {
            json.put("mlEngineStatus", "UNAVAILABLE");
            json.put("mlScoreBucket", "HIGH");
            json.put("mlRiskLevel", null);
            json.put("agreementStatus", "PARTIAL");
            json.put("riskMismatchStatus", "NOT_COMPARABLE");
            json.put("scoreDeltaBucket", "UNAVAILABLE");
        });

        Fixture inconsistentComparison = new Fixture();
        inconsistentComparison.agreementStatus = EngineIntelligenceAgreementStatus.DISAGREEMENT;
        assertRejectedByJavaAndSchema(inconsistentComparison, json -> json.put("agreementStatus", "DISAGREEMENT"));
    }

    @Test
    void rejectsMissingMandatoryEngineGroups() throws Exception {
        Fixture missingMl = new Fixture();
        missingMl.reasonCodesByEngine.remove("ml.python.primary");
        missingMl.reasonCodes = List.of("HIGH_RISK_FLAGS_PRESENT");
        assertRejectedByJavaAndSchema(missingMl, json -> {
            Map<String, Object> groups = object(json.get("reasonCodesByEngine"));
            groups.remove("ml.python.primary");
            json.put("reasonCodes", List.of("HIGH_RISK_FLAGS_PRESENT"));
        });
    }

    @Test
    void acceptsTwentyReasonCodeOccurrencesAndRejectsTwentyOne() throws Exception {
        Fixture twenty = new Fixture();
        twenty.reasonCodesByEngine = new LinkedHashMap<>();
        twenty.reasonCodesByEngine.put("rules.primary", repeated("HIGH_RISK_FLAGS_PRESENT", 5));
        twenty.reasonCodesByEngine.put("ml.python.primary", repeated("ML_MODEL_SIGNAL", 5));
        twenty.reasonCodesByEngine.put("velocity.primary", repeated("VELOCITY_FEATURES_UNAVAILABLE", 5));
        twenty.reasonCodesByEngine.put("device.primary", repeated("DEVICE_EVIDENCE_UNAVAILABLE", 5));
        twenty.reasonCodes = twenty.reasonCodesByEngine.values().stream().flatMap(List::stream).toList();
        twenty.deviceEngineStatus = FraudEngineStatus.DEGRADED;
        twenty.deviceScoreBucket = EngineIntelligenceScoreBucket.UNAVAILABLE;

        assertThat(twenty.build().reasonCodes()).hasSize(20);
        assertSchemaValid(payload(twenty.build()));

        Fixture twentyOne = new Fixture();
        List<String> overLimit = new ArrayList<>(twenty.reasonCodes);
        overLimit.add("ML_MODEL_SIGNAL");
        twentyOne.reasonCodes = List.copyOf(overLimit);
        twentyOne.reasonCodesByEngine = twenty.reasonCodesByEngine;
        twentyOne.deviceEngineStatus = FraudEngineStatus.DEGRADED;
        twentyOne.deviceScoreBucket = EngineIntelligenceScoreBucket.UNAVAILABLE;
        assertThatThrownBy(twentyOne::build).isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> twentyOneJson = payload(twenty.build());
        List<String> jsonCodes = new ArrayList<>(strings(twentyOneJson.get("reasonCodes")));
        jsonCodes.add("ML_MODEL_SIGNAL");
        twentyOneJson.put("reasonCodes", jsonCodes);
        assertSchemaInvalid(twentyOneJson);
    }

    @Test
    void constructorRejectsMapOrderAndFlattenedMismatchThatDraft202012CannotExpress() throws Exception {
        assertThat(OBJECT_MAPPER.readTree(Files.readString(SCHEMA_PATH)).at("/description").textValue())
                .contains("Java record constructor enforces canonical object-member order")
                .contains("cannot express those order and cross-property equality constraints");

        Fixture wrongOrder = new Fixture();
        wrongOrder.reasonCodesByEngine = new LinkedHashMap<>();
        wrongOrder.reasonCodesByEngine.put("ml.python.primary", List.of("ML_MODEL_SIGNAL"));
        wrongOrder.reasonCodesByEngine.put("rules.primary", List.of("HIGH_RISK_FLAGS_PRESENT"));
        wrongOrder.reasonCodes = List.of("ML_MODEL_SIGNAL", "HIGH_RISK_FLAGS_PRESENT");
        assertThatThrownBy(wrongOrder::build).isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> wrongOrderJson = payload(new Fixture().build());
        Map<String, Object> reversedGroups = new LinkedHashMap<>();
        reversedGroups.put("ml.python.primary", List.of("ML_MODEL_SIGNAL"));
        reversedGroups.put("rules.primary", List.of("HIGH_RISK_FLAGS_PRESENT"));
        wrongOrderJson.put("reasonCodesByEngine", reversedGroups);
        wrongOrderJson.put("reasonCodes", List.of("ML_MODEL_SIGNAL", "HIGH_RISK_FLAGS_PRESENT"));
        assertSchemaValid(wrongOrderJson);

        Fixture mismatchedFlatCodes = new Fixture();
        mismatchedFlatCodes.reasonCodes = List.of("HIGH_RISK_FLAGS_PRESENT");
        assertThatThrownBy(mismatchedFlatCodes::build).isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> mismatchJson = payload(new Fixture().build());
        mismatchJson.put("reasonCodes", List.of("HIGH_RISK_FLAGS_PRESENT"));
        assertSchemaValid(mismatchJson);
    }

    @Test
    void diagnosticsRemainBoundedAtFive() throws Exception {
        Fixture sixDiagnostics = new Fixture();
        sixDiagnostics.diagnosticSignals = List.of(
                "ML_MODEL_SIGNAL", "ML_MODEL_SIGNAL", "ML_MODEL_SIGNAL",
                "ML_MODEL_SIGNAL", "ML_MODEL_SIGNAL", "ML_MODEL_SIGNAL"
        );
        assertRejectedByJavaAndSchema(sixDiagnostics, json -> json.put("diagnosticSignals", sixDiagnostics.diagnosticSignals));
    }

    private static void assertRejectedByJavaAndSchema(Fixture fixture, Consumer<Map<String, Object>> mutateJson)
            throws Exception {
        assertThatThrownBy(fixture::build).isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> json = fixture.projectionStatus == EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING
                ? payload(Fixture.missing().build())
                : payload(new Fixture().build());
        mutateJson.accept(json);
        assertSchemaInvalid(json);
    }

    private static Map<String, Object> payload(EngineIntelligenceFeedbackDatasetRecord record) throws IOException {
        return OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(record),
                new TypeReference<LinkedHashMap<String, Object>>() { }
        );
    }

    private static List<Error> validateSchema(Map<String, Object> payload) throws IOException {
        return schema.validate(OBJECT_MAPPER.writeValueAsString(payload), InputFormat.JSON);
    }

    private static void assertSchemaValid(Map<String, Object> payload) throws IOException {
        assertThat(validateSchema(payload)).isEmpty();
    }

    private static void assertSchemaInvalid(Map<String, Object> payload) throws IOException {
        assertThat(validateSchema(payload)).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    private static List<String> repeated(String code, int count) {
        return java.util.Collections.nCopies(count, code);
    }

    private static Set<String> fixtureCodes(Map<String, Object> fixture, String field) {
        return Set.copyOf(strings(fixture.get(field)));
    }

    @SafeVarargs
    private static Set<String> merge(Set<String>... groups) {
        Set<String> merged = new LinkedHashSet<>();
        for (Set<String> group : groups) {
            merged.addAll(group);
        }
        return Set.copyOf(merged);
    }

    private static Fixture fixtureWithReasonCode(String engineId, String reasonCode) {
        Fixture fixture = new Fixture();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("rules.primary", engineId.equals(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID)
                ? List.of(reasonCode)
                : List.of("HIGH_RISK_FLAGS_PRESENT"));
        groups.put("ml.python.primary", engineId.equals(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID)
                ? List.of(reasonCode)
                : List.of("ML_MODEL_SIGNAL"));
        if (engineId.equals(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID)) {
            groups.put("velocity.primary", List.of(reasonCode));
        }
        if (engineId.equals(FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID)) {
            groups.put("device.primary", List.of(reasonCode));
            fixture.deviceEngineStatus = FraudEngineStatus.DEGRADED;
            fixture.deviceScoreBucket = EngineIntelligenceScoreBucket.UNAVAILABLE;
        }
        fixture.reasonCodesByEngine = groups;
        fixture.reasonCodes = groups.values().stream().flatMap(List::stream).toList();
        fixture.diagnosticSignals = List.of(reasonCode);
        return fixture;
    }

    private static Fixture datasetFixture(JsonNode engineIntelligence) {
        Fixture fixture = new Fixture();
        fixture.engineIntelligenceContractVersion = engineIntelligence.get("contractVersion").intValue();

        JsonNode comparison = engineIntelligence.get("comparison");
        fixture.agreementStatus = EngineIntelligenceAgreementStatus.valueOf(comparison.get("agreementStatus").asText());
        fixture.riskMismatchStatus = EngineIntelligenceRiskMismatchStatus.valueOf(comparison.get("riskMismatchStatus").asText());
        fixture.scoreDeltaBucket = EngineIntelligenceScoreDeltaBucket.valueOf(comparison.get("scoreDeltaBucket").asText());

        Map<String, List<String>> reasonCodesByEngine = new LinkedHashMap<>();
        List<String> reasonCodes = new ArrayList<>();
        for (JsonNode engine : engineIntelligence.get("engines")) {
            String engineId = engine.get("engineId").asText();
            List<String> engineReasonCodes = new ArrayList<>();
            engine.get("reasonCodes").forEach(reasonCode -> engineReasonCodes.add(reasonCode.asText()));
            reasonCodesByEngine.put(engineId, List.copyOf(engineReasonCodes));
            reasonCodes.addAll(engineReasonCodes);

            FraudEngineStatus status = FraudEngineStatus.valueOf(engine.get("status").asText());
            EngineIntelligenceScoreBucket scoreBucket = EngineIntelligenceScoreBucket.valueOf(engine.get("scoreBucket").asText());
            RiskLevel riskLevel = engine.get("riskLevel").isNull()
                    ? null
                    : RiskLevel.valueOf(engine.get("riskLevel").asText());
            switch (engineId) {
                case "rules.primary" -> {
                    fixture.rulesEngineStatus = status;
                    fixture.rulesScoreBucket = scoreBucket;
                    fixture.rulesRiskLevel = riskLevel;
                }
                case "ml.python.primary" -> {
                    fixture.mlEngineStatus = status;
                    fixture.mlScoreBucket = scoreBucket;
                    fixture.mlRiskLevel = riskLevel;
                }
                case "device.primary" -> {
                    fixture.deviceEngineStatus = status;
                    fixture.deviceScoreBucket = scoreBucket;
                    fixture.deviceRiskLevel = riskLevel;
                }
                default -> { }
            }
        }
        fixture.reasonCodesByEngine = reasonCodesByEngine;
        fixture.reasonCodes = List.copyOf(reasonCodes);

        List<String> diagnosticSignals = new ArrayList<>();
        engineIntelligence.get("diagnosticSignals")
                .forEach(signal -> diagnosticSignals.add(signal.get("reasonCode").asText()));
        fixture.diagnosticSignals = List.copyOf(diagnosticSignals);
        return fixture;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        return current.endsWith("alert-service") ? current.getParent() : current;
    }

    private static final class Fixture {
        private String evaluationRecordId = "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        private String transactionReference = "txnref-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        private Instant feedbackSubmittedAt = Instant.parse("2026-10-28T10:00:00Z");
        private EngineIntelligenceFeedbackDatasetLabel evaluationLabel =
                EngineIntelligenceFeedbackDatasetLabel.ANALYST_CONFIRMED_FRAUD;
        private EngineIntelligenceFeedbackDatasetLabelSource labelSource =
                EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION;
        private EngineIntelligenceFeedbackType feedbackType = EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS;
        private EngineIntelligenceFeedbackUsefulness usefulness = EngineIntelligenceFeedbackUsefulness.HELPFUL;
        private EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment =
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT;
        private EngineIntelligenceFeedbackDatasetProjectionStatus projectionStatus =
                EngineIntelligenceFeedbackDatasetProjectionStatus.AVAILABLE;
        private EngineIntelligenceAgreementStatus agreementStatus = EngineIntelligenceAgreementStatus.AGREEMENT;
        private EngineIntelligenceRiskMismatchStatus riskMismatchStatus =
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL;
        private EngineIntelligenceScoreDeltaBucket scoreDeltaBucket = EngineIntelligenceScoreDeltaBucket.NONE;
        private FraudEngineStatus mlEngineStatus = FraudEngineStatus.AVAILABLE;
        private EngineIntelligenceScoreBucket mlScoreBucket = EngineIntelligenceScoreBucket.HIGH;
        private RiskLevel mlRiskLevel = RiskLevel.HIGH;
        private FraudEngineStatus rulesEngineStatus = FraudEngineStatus.AVAILABLE;
        private EngineIntelligenceScoreBucket rulesScoreBucket = EngineIntelligenceScoreBucket.HIGH;
        private RiskLevel rulesRiskLevel = RiskLevel.HIGH;
        private List<String> reasonCodes = List.of("HIGH_RISK_FLAGS_PRESENT", "ML_MODEL_SIGNAL");
        private Map<String, List<String>> reasonCodesByEngine = defaultGroups();
        private List<String> diagnosticSignals = List.of();
        private Integer engineIntelligenceContractVersion = 2;
        private FraudEngineStatus deviceEngineStatus;
        private EngineIntelligenceScoreBucket deviceScoreBucket;
        private RiskLevel deviceRiskLevel;

        private static Fixture missing() {
            Fixture fixture = new Fixture();
            fixture.projectionStatus = EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING;
            fixture.agreementStatus = null;
            fixture.riskMismatchStatus = null;
            fixture.scoreDeltaBucket = null;
            fixture.mlEngineStatus = null;
            fixture.mlScoreBucket = null;
            fixture.mlRiskLevel = null;
            fixture.rulesEngineStatus = null;
            fixture.rulesScoreBucket = null;
            fixture.rulesRiskLevel = null;
            fixture.reasonCodes = List.of();
            fixture.reasonCodesByEngine = Map.of();
            fixture.diagnosticSignals = List.of();
            fixture.engineIntelligenceContractVersion = null;
            return fixture;
        }

        private EngineIntelligenceFeedbackDatasetRecord build() {
            return new EngineIntelligenceFeedbackDatasetRecord(
                    evaluationRecordId,
                    transactionReference,
                    feedbackSubmittedAt,
                    evaluationLabel,
                    labelSource,
                    feedbackType,
                    usefulness,
                    accuracyAssessment,
                    projectionStatus,
                    agreementStatus,
                    riskMismatchStatus,
                    scoreDeltaBucket,
                    mlEngineStatus,
                    mlScoreBucket,
                    mlRiskLevel,
                    rulesEngineStatus,
                    rulesScoreBucket,
                    rulesRiskLevel,
                    reasonCodes,
                    reasonCodesByEngine,
                    diagnosticSignals,
                    engineIntelligenceContractVersion,
                    deviceEngineStatus,
                    deviceScoreBucket,
                    deviceRiskLevel
            );
        }

        private static Map<String, List<String>> defaultGroups() {
            Map<String, List<String>> groups = new LinkedHashMap<>();
            groups.put("rules.primary", List.of("HIGH_RISK_FLAGS_PRESENT"));
            groups.put("ml.python.primary", List.of("ML_MODEL_SIGNAL"));
            return groups;
        }
    }
}
