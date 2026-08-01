package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.api.EngineIntelligenceComparisonResponse;
import com.frauddetection.alert.api.EngineIntelligenceDiagnosticSignalResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineStatusResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.api.MerchantInfoResponse;
import com.frauddetection.alert.api.MoneyResponse;
import com.frauddetection.alert.api.ScoredTransactionDetailResponse;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import com.frauddetection.common.events.recommendation.AnalystRecommendation;
import com.frauddetection.common.events.recommendation.AnalystRecommendationConfidence;
import com.frauddetection.common.events.recommendation.AnalystRecommendationNonDecisioning;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import com.frauddetection.common.events.recommendation.AnalystRecommendationSource;
import com.frauddetection.common.events.recommendation.AnalystRecommendationStatus;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceOpenApiInstanceValidationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final OpenApiInstanceValidator validator = new OpenApiInstanceValidator(schemas());

    @Test
    void instanceValidationAcceptsCanonicalValidResponse() {
        assertValid("EngineIntelligenceResponse", canonicalResponse());
    }

    @Test
    void instanceValidationAcceptsLegacyNormalizedValidResponseAfterMapping() {
        EngineIntelligenceProjection legacyProjection = new EngineIntelligenceProjection(
                "txn-legacy-normalized",
                1,
                Instant.parse("2026-06-18T10:00:02Z"),
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE,
                List.of(new EngineIntelligenceEngineProjection(
                        FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        FraudEngineType.RULES,
                        FraudEngineStatus.AVAILABLE,
                        RiskLevel.LOW,
                        EngineIntelligenceScoreBucket.LOW,
                        List.of("HIGH_VELOCITY")
                )),
                List.of(),
                List.of(),
                Instant.parse("2026-06-18T10:00:03Z"),
                Instant.parse("2026-06-18T10:00:04Z")
        );

        EngineIntelligenceResponse response = new EngineIntelligenceResponseMapper()
                .toResponse(new EngineIntelligenceReadModelMapper().map(legacyProjection));

        assertValid("EngineIntelligenceResponse", response);
        assertThat(response.comparison().comparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
    }

    @Test
    void instanceValidationAcceptsThreeEngineResponse() {
        assertValid("EngineIntelligenceResponse", threeEngineResponse());
    }

    @Test
    void instanceValidationAcceptsDegradedAbsentAndUnavailableResponses() {
        assertValid("EngineIntelligenceResponse", degradedResponse());
        assertValid("EngineIntelligenceResponse", EngineIntelligenceResponse.absent());
        assertValid("EngineIntelligenceResponse", EngineIntelligenceResponse.unavailable());
    }

    @Test
    void instanceValidationAcceptsValidAnalystRecommendation() {
        assertValid("AnalystRecommendationResult", analystRecommendation());
    }

    @Test
    void instanceValidationAcceptsScoredTransactionDetailResponse() {
        assertValid("ScoredTransactionDetailResponse", scoredTransactionDetail(canonicalResponse()));
    }

    @Test
    void instanceValidationRejectsNegativeInstances() {
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> instance.put("extra", "x")));
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> instance.remove("status")));
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> instance.put("generatedAt", "2026-06-11T10:15:30+00:00")));
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> engine(instance, 0).put("reasonCodes", List.of("A".repeat(129)))));
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> comparison(instance).put("comparedEngineIds", List.of("ml.python.primary", "rules.primary"))));
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> engine(instance, 0).put("riskLevel", null)));
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> engine(instance, 0).put("engineId", "unknown.primary")));
        assertInvalid("EngineIntelligenceResponse", mutate(threeEngineResponse(), instance -> engines(instance).add(engineMap(
                "velocity.primary",
                "VELOCITY",
                "AVAILABLE",
                "HIGH",
                "HIGH",
                List.of("RAPID_TRANSFER_BURST_SIGNAL")
        ))));
    }

    @Test
    void instanceValidationUsesSharedTimestampMatrix() throws Exception {
        for (Map<String, Object> timestampCase : publicApiFixture("canonical-utc-timestamp-cases.json")) {
            String caseId = timestampCase.get("caseId").toString();
            boolean valid = Boolean.TRUE.equals(timestampCase.get("valid"));
            Object value = timestampCase.get("value");
            List<String> errors = validate("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> instance.put("generatedAt", value)));

            assertThat(errors.isEmpty())
                    .as(caseId)
                    .isEqualTo(valid);
        }
    }

    @Test
    void instanceValidationUsesSharedBoundedStringMatrix() throws Exception {
        for (Map<String, Object> stringCase : publicApiFixture("public-string-boundary-cases.json")) {
            if (!Integer.valueOf(128).equals(stringCase.get("maxLength"))) {
                continue;
            }
            String caseId = stringCase.get("caseId").toString();
            boolean valid = Boolean.TRUE.equals(stringCase.get("valid"));
            Object value = stringCase.get("value");
            List<String> errors = validate("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> engine(instance, 0).put("reasonCodes", List.of(value))));

            assertThat(errors.isEmpty())
                    .as(caseId)
                    .isEqualTo(valid);
        }
    }

    private void assertValid(String schemaName, Object instance) {
        assertThat(validate(schemaName, instance)).isEmpty();
    }

    private void assertInvalid(String schemaName, Object instance) {
        assertThat(validate(schemaName, instance)).isNotEmpty();
    }

    private List<String> validate(String schemaName, Object instance) {
        List<String> errors = new ArrayList<>(validator.validate(schemaName, toMap(instance)));
        if ("EngineIntelligenceResponse".equals(schemaName)) {
            errors.addAll(validateEngineIntelligenceSemantics(toMap(instance)));
        }
        if ("ScoredTransactionDetailResponse".equals(schemaName)) {
            Map<String, Object> detail = toMap(instance);
            errors.addAll(validateEngineIntelligenceSemantics(map(detail.get("engineIntelligence"))));
        }
        return errors;
    }

    private EngineIntelligenceResponse canonicalResponse() {
        return new EngineIntelligenceResponse(
                EngineIntelligenceResponseStatus.AVAILABLE,
                1,
                Instant.parse("2026-06-18T10:00:02Z"),
                comparisonResponse(EngineIntelligenceAgreementStatus.PARTIAL),
                List.of(engineResponse(
                        FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        FraudEngineType.RULES,
                        EngineIntelligenceEngineStatusResponse.AVAILABLE,
                        RiskLevel.CRITICAL,
                        EngineIntelligenceScoreBucket.HIGH,
                        List.of("HIGH_VELOCITY")
                )),
                List.of(signalResponse(
                        FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        FraudEngineType.RULES,
                        EngineIntelligenceEngineStatusResponse.AVAILABLE,
                        EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                        RiskLevel.CRITICAL,
                        EngineIntelligenceScoreBucket.HIGH,
                        "HIGH_VELOCITY"
                )),
                List.of()
        );
    }

    private EngineIntelligenceResponse threeEngineResponse() {
        return new EngineIntelligenceResponse(
                EngineIntelligenceResponseStatus.AVAILABLE,
                1,
                Instant.parse("2026-06-18T10:00:02Z"),
                comparisonResponse(EngineIntelligenceAgreementStatus.AGREEMENT),
                List.of(
                        engineResponse("rules.primary", FraudEngineType.RULES, EngineIntelligenceEngineStatusResponse.AVAILABLE, RiskLevel.LOW, EngineIntelligenceScoreBucket.LOW, List.of("HIGH_VELOCITY")),
                        engineResponse("ml.python.primary", FraudEngineType.ML_MODEL, EngineIntelligenceEngineStatusResponse.AVAILABLE, RiskLevel.LOW, EngineIntelligenceScoreBucket.LOW, List.of("ML_MODEL_SIGNAL")),
                        engineResponse("velocity.primary", FraudEngineType.VELOCITY, EngineIntelligenceEngineStatusResponse.AVAILABLE, RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH, List.of("RAPID_TRANSFER_BURST_SIGNAL"))
                ),
                List.of(signalResponse("velocity.primary", FraudEngineType.VELOCITY, EngineIntelligenceEngineStatusResponse.AVAILABLE, EngineIntelligenceSignalCategory.FRAUD_SIGNAL, RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH, "RAPID_TRANSFER_BURST_SIGNAL")),
                List.of()
        );
    }

    private EngineIntelligenceResponse degradedResponse() {
        return new EngineIntelligenceResponse(
                EngineIntelligenceResponseStatus.DEGRADED,
                1,
                Instant.parse("2026-06-18T10:00:02Z"),
                comparisonResponse(EngineIntelligenceAgreementStatus.PARTIAL),
                List.of(engineResponse(
                        "ml.python.primary",
                        FraudEngineType.ML_MODEL,
                        EngineIntelligenceEngineStatusResponse.TIMEOUT,
                        null,
                        EngineIntelligenceScoreBucket.UNAVAILABLE,
                        List.of("ML_MODEL_TIMEOUT")
                )),
                List.of(signalResponse(
                        "ml.python.primary",
                        FraudEngineType.ML_MODEL,
                        EngineIntelligenceEngineStatusResponse.TIMEOUT,
                        EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                        null,
                        EngineIntelligenceScoreBucket.UNAVAILABLE,
                        "ML_MODEL_TIMEOUT"
                )),
                List.of(new com.frauddetection.alert.api.EngineIntelligenceWarningResponse(
                        EngineIntelligenceWarningCode.ENGINE_RESULT_LIMIT_APPLIED,
                        1
                ))
        );
    }

    private EngineIntelligenceComparisonResponse comparisonResponse(EngineIntelligenceAgreementStatus agreementStatus) {
        return new EngineIntelligenceComparisonResponse(
                EngineIntelligenceComparisonType.RULES_VS_ML,
                FraudEngineIdentityContract.rulesVsMlComparisonEngineIds(),
                agreementStatus,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
        );
    }

    private EngineIntelligenceEngineResponse engineResponse(
            String engineId,
            FraudEngineType engineType,
            EngineIntelligenceEngineStatusResponse status,
            RiskLevel riskLevel,
            EngineIntelligenceScoreBucket scoreBucket,
            List<String> reasonCodes
    ) {
        return new EngineIntelligenceEngineResponse(engineId, engineType, status, riskLevel, scoreBucket, reasonCodes);
    }

    private EngineIntelligenceDiagnosticSignalResponse signalResponse(
            String engineId,
            FraudEngineType engineType,
            EngineIntelligenceEngineStatusResponse engineStatus,
            EngineIntelligenceSignalCategory signalCategory,
            RiskLevel riskLevel,
            EngineIntelligenceScoreBucket scoreBucket,
            String reasonCode
    ) {
        return new EngineIntelligenceDiagnosticSignalResponse(
                engineId,
                engineType,
                engineStatus,
                signalCategory,
                riskLevel,
                scoreBucket,
                reasonCode
        );
    }

    private AnalystRecommendationResult analystRecommendation() {
        return new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                Instant.parse("2026-06-19T10:00:00Z"),
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.RULES_RISK,
                List.of("RULES_HIGH_RISK"),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        );
    }

    private ScoredTransactionDetailResponse scoredTransactionDetail(EngineIntelligenceResponse engineIntelligence) {
        return new ScoredTransactionDetailResponse(
                "txn-detail-1",
                "customer-1",
                "corr-1",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z"),
                new MoneyResponse(new BigDecimal("100.00"), "USD"),
                new MerchantInfoResponse("merchant-1", "Store", "5411", "GROCERY", "US", "ECOMMERCE", false, Map.of()),
                0.91d,
                RiskLevel.CRITICAL,
                true,
                List.of("HIGH_VELOCITY"),
                engineIntelligence,
                analystRecommendation()
        );
    }

    private Map<String, Object> mutate(Object instance, java.util.function.Consumer<Map<String, Object>> mutator) {
        Map<String, Object> copy = deepCopy(toMap(instance));
        mutator.accept(copy);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> comparison(Map<String, Object> instance) {
        return (Map<String, Object>) instance.get("comparison");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> engines(Map<String, Object> instance) {
        return (List<Map<String, Object>>) instance.get("engines");
    }

    private Map<String, Object> engine(Map<String, Object> instance, int index) {
        return engines(instance).get(index);
    }

    private Map<String, Object> engineMap(
            String engineId,
            String engineType,
            String status,
            String riskLevel,
            String scoreBucket,
            List<String> reasonCodes
    ) {
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("engineId", engineId);
        engine.put("engineType", engineType);
        engine.put("status", status);
        engine.put("riskLevel", riskLevel);
        engine.put("scoreBucket", scoreBucket);
        engine.put("reasonCodes", reasonCodes);
        return engine;
    }

    private List<String> validateEngineIntelligenceSemantics(Map<String, Object> instance) {
        List<String> errors = new ArrayList<>();
        if (instance == null) {
            return errors;
        }
        Object status = instance.get("status");
        if ("ABSENT".equals(status) || "UNAVAILABLE".equals(status)) {
            if (instance.get("contractVersion") != null || instance.get("generatedAt") != null || instance.get("comparison") != null
                    || !list(instance.get("engines")).isEmpty() || !list(instance.get("diagnosticSignals")).isEmpty() || !list(instance.get("warnings")).isEmpty()) {
                errors.add("unavailable/absent engine intelligence must not expose projected fields");
            }
            return errors;
        }
        if (!FraudEngineIdentityContract.rulesVsMlComparisonEngineIds().equals(list(comparison(instance).get("comparedEngineIds")))) {
            errors.add("comparison ids must be exact and ordered");
        }
        for (Map<String, Object> engine : engines(instance)) {
            if (!FraudEngineIdentityContract.hasExpectedType(engine.get("engineId").toString(), FraudEngineType.valueOf(engine.get("engineType").toString()))) {
                errors.add("unknown or mismatched engine identity");
            }
            boolean available = "AVAILABLE".equals(engine.get("status"));
            boolean riskPresent = engine.get("riskLevel") != null;
            boolean usableBucket = List.of("LOW", "MEDIUM", "HIGH", "VERY_HIGH").contains(engine.get("scoreBucket"));
            if (available && (!riskPresent || !usableBucket)) {
                errors.add("available engine must expose risk and usable bucket");
            }
            if (!available && (riskPresent || !"UNAVAILABLE".equals(engine.get("scoreBucket")))) {
                errors.add("non-available engine must not expose risk");
            }
        }
        for (Object signalObject : list(instance.get("diagnosticSignals"))) {
            Map<String, Object> signal = map(signalObject);
            boolean fraudSignal = "FRAUD_SIGNAL".equals(signal.get("signalCategory"));
            boolean available = "AVAILABLE".equals(signal.get("engineStatus"));
            boolean riskPresent = signal.get("riskLevel") != null;
            boolean usableBucket = List.of("LOW", "MEDIUM", "HIGH", "VERY_HIGH").contains(signal.get("scoreBucket"));
            if (available && fraudSignal && (!riskPresent || !usableBucket)) {
                errors.add("available fraud signal must expose risk and usable bucket");
            }
            if ((!available || !fraudSignal) && (riskPresent || !"UNAVAILABLE".equals(signal.get("scoreBucket")))) {
                errors.add("operational signal must not expose fraud risk");
            }
        }
        return errors;
    }

    private List<Map<String, Object>> publicApiFixture(String name) throws Exception {
        Map<String, Object> root = objectMapper.readValue(
                publicApiFixturePath(name).toFile(),
                new TypeReference<>() {
                }
        );
        return list(root.get("cases"));
    }

    private Path publicApiFixturePath(String name) {
        Path fromRoot = Path.of("contract-fixtures", "public-api", name);
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..", "contract-fixtures", "public-api", name);
    }

    private Map<String, Object> schemas() {
        try {
            Map<String, Object> root = map(new Yaml().load(Files.readString(openApiPath())));
            Map<String, Object> components = map(root.get("components"));
            return map(components.get("schemas"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Path openApiPath() {
        Path fromRoot = Path.of("docs", "openapi", "alert_service.openapi.yaml");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..", "docs", "openapi", "alert_service.openapi.yaml");
    }

    private Map<String, Object> toMap(Object instance) {
        return objectMapper.convertValue(instance, new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> list(Object value) {
        return value == null ? List.of() : (List<T>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        return objectMapper.convertValue(source, new TypeReference<>() {
        });
    }

    private static final class OpenApiInstanceValidator {
        private final Map<String, Object> schemas;

        private OpenApiInstanceValidator(Map<String, Object> schemas) {
            this.schemas = schemas;
        }

        private List<String> validate(String schemaName, Map<String, Object> instance) {
            return validateSchema(schema(schemaName), instance, schemaName);
        }

        private List<String> validateSchema(Map<String, Object> schema, Object instance, String path) {
            if (schema.containsKey("$ref")) {
                return validateSchema(ref(schema.get("$ref").toString()), instance, path);
            }
            if (instance == null) {
                return Boolean.TRUE.equals(schema.get("nullable")) ? List.of() : List.of(path + " is null");
            }
            List<String> errors = new ArrayList<>();
            errors.addAll(validateEnum(schema, instance, path));
            errors.addAll(validateAllOf(schema, instance, path));
            errors.addAll(validateOneOf(schema, instance, path));
            Object type = schema.get("type");
            if ("object".equals(type)) {
                errors.addAll(validateObject(schema, instance, path));
            } else if ("array".equals(type)) {
                errors.addAll(validateArray(schema, instance, path));
            } else if ("string".equals(type)) {
                errors.addAll(validateString(schema, instance, path));
            } else if ("integer".equals(type)) {
                if (!(instance instanceof Integer) && !(instance instanceof Long)) {
                    errors.add(path + " is not an integer");
                }
            } else if ("number".equals(type)) {
                if (!(instance instanceof Number)) {
                    errors.add(path + " is not a number");
                }
            } else if ("boolean".equals(type) && !(instance instanceof Boolean)) {
                errors.add(path + " is not a boolean");
            }
            return errors;
        }

        private List<String> validateObject(Map<String, Object> schema, Object instance, String path) {
            if (!(instance instanceof Map<?, ?> object)) {
                return List.of(path + " is not an object");
            }
            Map<String, Object> properties = map(schema.getOrDefault("properties", Map.of()));
            List<String> errors = new ArrayList<>();
            for (Object required : list(schema.get("required"))) {
                if (!object.containsKey(required)) {
                    errors.add(path + "." + required + " is required");
                }
            }
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                for (Object key : object.keySet()) {
                    if (!properties.containsKey(key.toString())) {
                        errors.add(path + "." + key + " is not allowed");
                    }
                }
            }
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                if (object.containsKey(property.getKey())) {
                    errors.addAll(validateSchema(map(property.getValue()), object.get(property.getKey()), path + "." + property.getKey()));
                }
            }
            return errors;
        }

        private List<String> validateArray(Map<String, Object> schema, Object instance, String path) {
            if (!(instance instanceof List<?> values)) {
                return List.of(path + " is not an array");
            }
            List<String> errors = new ArrayList<>();
            Integer minItems = integer(schema.get("minItems"));
            Integer maxItems = integer(schema.get("maxItems"));
            if (minItems != null && values.size() < minItems) {
                errors.add(path + " has too few items");
            }
            if (maxItems != null && values.size() > maxItems) {
                errors.add(path + " has too many items");
            }
            if (Boolean.TRUE.equals(schema.get("uniqueItems")) && values.stream().distinct().count() != values.size()) {
                errors.add(path + " has duplicate items");
            }
            Map<String, Object> itemSchema = map(schema.get("items"));
            for (int index = 0; index < values.size(); index++) {
                errors.addAll(validateSchema(itemSchema, values.get(index), path + "[" + index + "]"));
            }
            return errors;
        }

        private List<String> validateString(Map<String, Object> schema, Object instance, String path) {
            if (!(instance instanceof String value)) {
                return List.of(path + " is not a string");
            }
            List<String> errors = new ArrayList<>();
            Integer minLength = integer(schema.get("minLength"));
            Integer maxLength = integer(schema.get("maxLength"));
            if (minLength != null && value.length() < minLength) {
                errors.add(path + " is too short");
            }
            if (maxLength != null && value.length() > maxLength) {
                errors.add(path + " is too long");
            }
            Object pattern = schema.get("pattern");
            if (pattern != null && !Pattern.compile(pattern.toString()).matcher(value).matches()) {
                errors.add(path + " does not match pattern");
            }
            return errors;
        }

        private List<String> validateEnum(Map<String, Object> schema, Object instance, String path) {
            if (!schema.containsKey("enum")) {
                return List.of();
            }
            return list(schema.get("enum")).contains(instance) ? List.of() : List.of(path + " is not in enum");
        }

        private List<String> validateAllOf(Map<String, Object> schema, Object instance, String path) {
            List<String> errors = new ArrayList<>();
            for (Object child : list(schema.get("allOf"))) {
                errors.addAll(validateSchema(map(child), instance, path));
            }
            return errors;
        }

        private List<String> validateOneOf(Map<String, Object> schema, Object instance, String path) {
            List<Object> oneOf = list(schema.get("oneOf"));
            if (oneOf.isEmpty()) {
                return List.of();
            }
            long matches = oneOf.stream()
                    .filter(child -> validateSchema(map(child), instance, path).isEmpty())
                    .count();
            return matches == 1 ? List.of() : List.of(path + " must match exactly one schema");
        }

        private Map<String, Object> schema(String name) {
            return map(schemas.get(name));
        }

        private Map<String, Object> ref(String ref) {
            return schema(ref.substring(ref.lastIndexOf('/') + 1));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Object value) {
            return value == null ? Map.of() : (Map<String, Object>) value;
        }

        @SuppressWarnings("unchecked")
        private static <T> List<T> list(Object value) {
            return value == null ? List.of() : (List<T>) value;
        }

        private static Integer integer(Object value) {
            return value instanceof Number number ? number.intValue() : null;
        }
    }
}
