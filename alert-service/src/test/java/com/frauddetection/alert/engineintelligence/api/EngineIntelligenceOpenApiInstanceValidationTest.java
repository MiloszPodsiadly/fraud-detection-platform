package com.frauddetection.alert.engineintelligence.api;

import com.github.fge.jackson.JsonLoader;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingMessage;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
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

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceOpenApiInstanceValidationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final OpenApiInstanceValidator validator = new OpenApiInstanceValidator(schemas(), objectMapper);

    @Test
    void instanceValidationAcceptsCanonicalValidResponse() {
        assertValid("EngineIntelligenceResponse", canonicalResponse());
    }

    @Test
    void instanceValidationAcceptsLegacyIncompleteResponseAsUnavailableAfterMapping() {
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

        EngineIntelligenceResponse response;
        try {
            response = new EngineIntelligenceResponseMapper()
                    .toResponse(new EngineIntelligenceReadModelMapper().map(legacyProjection));
        } catch (EngineIntelligenceProjectionReadUnavailableException exception) {
            response = new EngineIntelligenceResponseMapper().unavailable();
        }

        assertValid("EngineIntelligenceResponse", response);
        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
        assertThat(response.comparison()).isNull();
    }

    @Test
    void instanceValidationAcceptsThreeEngineResponse() {
        assertValid("EngineIntelligenceResponse", threeEngineResponse());
    }

    @Test
    void instanceValidationAcceptsFullPathCompositionFixture() throws Exception {
        assertValid("EngineIntelligenceResponse", publicApiFixtureMap("engine-intelligence-full-path-composition-response.json"));
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
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> engine(instance, 0).put("status", null)));
        assertInvalid("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> instance.put("engines", List.of(engine(instance, 0)))));
        assertInvalid("EngineIntelligenceResponse", mutate(EngineIntelligenceResponse.unavailable(), instance -> instance.put("generatedAt", "2026-06-18T10:00:02Z")));
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
            List<String> errors = validate("EngineIntelligenceResponse", mutate(canonicalResponse(), instance -> {
                engine(instance, 0).put("reasonCodes", List.of(value));
                instance.put("diagnosticSignals", List.of());
            }));

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
        if (!errors.isEmpty()) {
            return errors;
        }
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
                comparisonResponse(
                        EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        EngineIntelligenceScoreDeltaBucket.LARGE
                ),
                List.of(
                        engineResponse(
                                FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                                FraudEngineType.RULES,
                                EngineIntelligenceEngineStatusResponse.AVAILABLE,
                                RiskLevel.CRITICAL,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        engineResponse(
                                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                                FraudEngineType.ML_MODEL,
                                EngineIntelligenceEngineStatusResponse.AVAILABLE,
                                RiskLevel.LOW,
                                EngineIntelligenceScoreBucket.LOW,
                                List.of("LOW_MODEL_RISK")
                        )
                ),
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
                comparisonResponse(
                        EngineIntelligenceAgreementStatus.AGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                        EngineIntelligenceScoreDeltaBucket.NONE
                ),
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
                comparisonResponse(
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(
                        engineResponse(
                                "rules.primary",
                                FraudEngineType.RULES,
                                EngineIntelligenceEngineStatusResponse.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.HIGH,
                                List.of("HIGH_VELOCITY")
                        ),
                        engineResponse(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                EngineIntelligenceEngineStatusResponse.TIMEOUT,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("ML_MODEL_TIMEOUT")
                        )
                ),
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

    private EngineIntelligenceComparisonResponse comparisonResponse(
            EngineIntelligenceAgreementStatus agreementStatus,
            EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
            EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
    ) {
        return new EngineIntelligenceComparisonResponse(
                EngineIntelligenceComparisonType.RULES_VS_ML,
                FraudEngineIdentityContract.rulesVsMlComparisonEngineIds(),
                agreementStatus,
                riskMismatchStatus,
                scoreDeltaBucket
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
        Map<String, Map<String, Object>> enginesById = new LinkedHashMap<>();
        int previousOrder = -1;
        for (Map<String, Object> engine : engines(instance)) {
            String engineId = engine.get("engineId").toString();
            if (!FraudEngineIdentityContract.hasExpectedType(engineId, FraudEngineType.valueOf(engine.get("engineType").toString()))) {
                errors.add("unknown or mismatched engine identity");
            }
            int currentOrder = FraudEngineIdentityContract.orderOf(engineId);
            if (enginesById.put(engineId, engine) != null || currentOrder <= previousOrder) {
                errors.add("engine ids must be unique and canonical ordered");
            }
            previousOrder = currentOrder;
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
        if (enginesById.size() != 2 && enginesById.size() != 3) {
            errors.add("engine set must contain Rules and ML with optional Velocity");
        }
        if (!enginesById.containsKey("rules.primary") || !enginesById.containsKey("ml.python.primary")) {
            errors.add("required Rules and ML engines missing");
        }
        if (enginesById.size() == 3 && !enginesById.containsKey("velocity.primary")) {
            errors.add("third engine must be Velocity");
        }
        if (enginesById.containsKey("rules.primary") && enginesById.containsKey("ml.python.primary")) {
            errors.addAll(validateComparisonCoherence(comparison(instance), enginesById.get("rules.primary"), enginesById.get("ml.python.primary")));
        }
        for (Object signalObject : list(instance.get("diagnosticSignals"))) {
            Map<String, Object> signal = map(signalObject);
            Map<String, Object> engine = enginesById.get(signal.get("engineId").toString());
            if (engine == null) {
                errors.add("diagnostic signal engine must exist");
                continue;
            }
            if (!signal.get("engineType").equals(engine.get("engineType")) || !signal.get("engineStatus").equals(engine.get("status"))) {
                errors.add("diagnostic signal must match engine identity and status");
            }
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
            if (!list(engine.get("reasonCodes")).contains(signal.get("reasonCode"))) {
                errors.add("diagnostic signal reason must belong to engine reason codes");
            }
        }
        return errors;
    }

    private List<String> validateComparisonCoherence(
            Map<String, Object> comparison,
            Map<String, Object> rules,
            Map<String, Object> ml
    ) {
        boolean rulesAvailable = "AVAILABLE".equals(rules.get("status"));
        boolean mlAvailable = "AVAILABLE".equals(ml.get("status"));
        if (!rulesAvailable || !mlAvailable) {
            String expectedAgreement = rulesAvailable ? "PARTIAL" : "REQUIRED_ENGINE_NOT_COMPARABLE";
            if (!"NOT_COMPARABLE".equals(comparison.get("riskMismatchStatus"))
                    || !"UNAVAILABLE".equals(comparison.get("scoreDeltaBucket"))
                    || !expectedAgreement.equals(comparison.get("agreementStatus"))) {
                return List.of("operational comparison must not expose risk delta");
            }
            return List.of();
        }
        String expectedRiskMismatch = riskMismatch(rules.get("riskLevel").toString(), ml.get("riskLevel").toString());
        String expectedAgreement = agreementFor(expectedRiskMismatch);
        Object delta = comparison.get("scoreDeltaBucket");
        if (!expectedRiskMismatch.equals(comparison.get("riskMismatchStatus"))
                || !expectedAgreement.equals(comparison.get("agreementStatus"))
                || "UNAVAILABLE".equals(delta)
                || !deltaBucketCanDescribe(rules.get("scoreBucket").toString(), ml.get("scoreBucket").toString(), delta.toString())) {
            return List.of("available comparison must describe Rules and ML risk/score relationship");
        }
        return List.of();
    }

    private String riskMismatch(String rulesRiskLevel, String mlRiskLevel) {
        int distance = Math.abs(riskSeverity(rulesRiskLevel) - riskSeverity(mlRiskLevel));
        if (distance == 0) {
            return "SAME_RISK_LEVEL";
        }
        if (distance == 1) {
            return "ADJACENT_RISK_LEVEL";
        }
        return "MATERIAL_RISK_MISMATCH";
    }

    private String agreementFor(String riskMismatch) {
        return switch (riskMismatch) {
            case "SAME_RISK_LEVEL" -> "AGREEMENT";
            case "ADJACENT_RISK_LEVEL" -> "ADJACENT_RISK_VARIANCE";
            default -> "DISAGREEMENT";
        };
    }

    private int riskSeverity(String riskLevel) {
        return switch (riskLevel) {
            case "LOW" -> 0;
            case "MEDIUM" -> 1;
            case "HIGH" -> 2;
            case "CRITICAL" -> 3;
            default -> -1;
        };
    }

    private boolean deltaBucketCanDescribe(String rulesScoreBucket, String mlScoreBucket, String scoreDeltaBucket) {
        if ("UNAVAILABLE".equals(scoreDeltaBucket)) {
            return false;
        }
        if (rulesScoreBucket.equals(mlScoreBucket)) {
            return !"LARGE".equals(scoreDeltaBucket);
        }
        if ("NONE".equals(scoreDeltaBucket)) {
            return false;
        }
        if (("LOW".equals(rulesScoreBucket) && "VERY_HIGH".equals(mlScoreBucket))
                || ("VERY_HIGH".equals(rulesScoreBucket) && "LOW".equals(mlScoreBucket))) {
            return "LARGE".equals(scoreDeltaBucket);
        }
        return true;
    }

    private List<Map<String, Object>> publicApiFixture(String name) throws Exception {
        Map<String, Object> root = objectMapper.readValue(
                publicApiFixturePath(name).toFile(),
                new TypeReference<>() {
                }
        );
        return list(root.get("cases"));
    }

    private Map<String, Object> publicApiFixtureMap(String name) throws Exception {
        return objectMapper.readValue(
                publicApiFixturePath(name).toFile(),
                new TypeReference<>() {
                }
        );
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
        private final ObjectMapper objectMapper;
        private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.byDefault();
        private final Map<String, Object> definitions;

        private OpenApiInstanceValidator(Map<String, Object> schemas, ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            this.definitions = convertSchemas(schemas);
        }

        private List<String> validate(String schemaName, Map<String, Object> instance) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("$schema", "http://json-schema.org/draft-04/schema#");
            document.put("$ref", "#/definitions/" + schemaName);
            document.put("definitions", definitions);
            try {
                JsonSchema schema = schemaFactory.getJsonSchema(JsonLoader.fromString(objectMapper.writeValueAsString(document)));
                ProcessingReport report = schema.validate(JsonLoader.fromString(objectMapper.writeValueAsString(instance)));
                if (report.isSuccess()) {
                    return List.of();
                }
                List<String> errors = new ArrayList<>();
                for (ProcessingMessage message : report) {
                    errors.add(message.getMessage());
                }
                return errors;
            } catch (ProcessingException | java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private Map<String, Object> convertSchemas(Map<String, Object> schemas) {
            Map<String, Object> converted = new LinkedHashMap<>();
            schemas.forEach((name, schema) -> converted.put(name, convert(schema)));
            return converted;
        }

        private Object convert(Object value) {
            if (value instanceof Map<?, ?> source) {
                Map<String, Object> target = new LinkedHashMap<>();
                boolean nullable = Boolean.TRUE.equals(source.get("nullable"));
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    String key = entry.getKey().toString();
                    if ("nullable".equals(key)) {
                        continue;
                    }
                    if ("$ref".equals(key)) {
                        target.put(key, entry.getValue().toString().replace("#/components/schemas/", "#/definitions/"));
                    } else {
                        target.put(key, convert(entry.getValue()));
                    }
                }
                if (nullable) {
                    addNullable(target);
                }
                return target;
            }
            if (value instanceof List<?> source) {
                return source.stream().map(this::convert).toList();
            }
            return value;
        }

        private void addNullable(Map<String, Object> schema) {
            Object type = schema.get("type");
            if (type instanceof String typeName) {
                schema.put("type", List.of(typeName, "null"));
            }
            if (schema.containsKey("enum")) {
                List<Object> values = new ArrayList<>(list(schema.get("enum")));
                if (!values.contains(null)) {
                    values.add(null);
                }
                schema.put("enum", values);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> List<T> list(Object value) {
            return value == null ? List.of() : (List<T>) value;
        }
    }
}
