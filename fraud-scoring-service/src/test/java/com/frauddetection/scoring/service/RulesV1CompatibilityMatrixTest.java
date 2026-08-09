package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RulesV1CompatibilityMatrixTest {
    private static final Path MATRIX = Path.of(
            "src/test/resources/fixtures/rules/rules_v1_compatibility_matrix.json"
    );
    private static final Set<String> CONTRIBUTION_KEY_SUFFIXES = Set.of("Weight", "Boost");

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final RuleBasedFraudScoringEngine engine =
            new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED));

    @Test
    void rulesV1CompatibilityMatrixMatchesFrozenExpectedResults() throws Exception {
        JsonNode matrix = matrix();

        assertFixtureCoverage(matrix);
        assertExpectedResultsGuard(matrix);
        for (JsonNode compatibilityCase : matrix.get("cases")) {
            TransactionEnrichedEvent event = eventFrom(compatibilityCase);
            RulesInputValidationResult validation = RulesFeatureInputValidator.validate(
                    event,
                    new FeatureSnapshotReader(event.featureSnapshot())
            );
            JsonNode expected = compatibilityCase.get("expected");

            assertThat(validation.status().name())
                    .as(caseId(compatibilityCase))
                    .isEqualTo(expected.get("validityResult").textValue());

            if (!validation.valid()) {
                assertInvalidCase(event, compatibilityCase);
                continue;
            }

            FraudScoreResult result = engine.score(FraudScoringRequest.from(event));
            assertThat(result.fraudScore())
                    .as(caseId(compatibilityCase))
                    .isCloseTo(expected.get("score").doubleValue(), within(0.000001d));
            assertThat(result.riskLevel())
                    .as(caseId(compatibilityCase))
                    .isEqualTo(RiskLevel.valueOf(expected.get("riskLevel").textValue()));
            assertThat(result.alertRecommended())
                    .as(caseId(compatibilityCase))
                    .isEqualTo(expected.get("alertRecommended").booleanValue());
            assertThat(result.reasonCodes())
                    .as(caseId(compatibilityCase))
                    .containsExactlyElementsOf(textValues(expected.get("reasonCodes")));
            assertThat(contributionKeys(result.scoreDetails()))
                    .as(caseId(compatibilityCase))
                    .containsExactlyElementsOf(textValues(expected.get("contributionKeys")));
            assertThat(result.modelName()).isEqualTo(matrix.get("source").get("modelName").textValue());
            assertThat(result.modelVersion()).isEqualTo(matrix.get("source").get("modelVersion").textValue());
        }
    }

    @Test
    void modelVersionV1IsRetainedOnlyWhileCompatibilityMatrixPasses() throws Exception {
        JsonNode matrix = matrix();

        for (JsonNode compatibilityCase : matrix.get("cases")) {
            TransactionEnrichedEvent event = eventFrom(compatibilityCase);
            RulesInputValidationResult validation = RulesFeatureInputValidator.validate(
                    event,
                    new FeatureSnapshotReader(event.featureSnapshot())
            );
            if (validation.valid()) {
                FraudScoreResult result = engine.score(FraudScoringRequest.from(event));
                assertThat(result.modelName()).as(caseId(compatibilityCase)).isEqualTo("rule-based-engine");
                assertThat(result.modelVersion()).as(caseId(compatibilityCase)).isEqualTo("v1");
            }
        }
    }

    private void assertInvalidCase(TransactionEnrichedEvent event, JsonNode compatibilityCase) {
        assertThatThrownBy(() -> engine.score(FraudScoringRequest.from(event)))
                .as(caseId(compatibilityCase))
                .isInstanceOf(RulesFeatureInputValidationException.class)
                .hasMessage("RULES_FEATURE_INPUT_INVALID")
                .hasMessageNotContaining(caseId(compatibilityCase))
                .hasMessageNotContaining("P1D")
                .hasMessageNotContaining("PT5M")
                .hasMessageNotContaining("JPY")
                .hasMessageNotContaining("5")
                .hasMessageNotContaining("20000");
    }

    private void assertFixtureCoverage(JsonNode matrix) {
        assertThat(textValues(matrix.get("cases"), "representationType"))
                .containsExactlyInAnyOrderElementsOf(List.of(
                        "CANONICAL_CURRENT_PRODUCER",
                        "CANONICAL_CURRENT_PRODUCER",
                        "CANONICAL_CURRENT_PRODUCER",
                        "TOP_LEVEL_ONLY",
                        "TOP_LEVEL_ONLY",
                        "FLAG_ONLY",
                        "TOP_LEVEL_ONLY",
                        "CONTRADICTORY_LEGACY",
                        "CANONICAL_CURRENT_PRODUCER",
                        "CANONICAL_CURRENT_PRODUCER",
                        "TOP_LEVEL_ONLY",
                        "FLAG_ONLY",
                        "MATCHING_REDUNDANT",
                        "CONTRADICTORY_LEGACY",
                        "CANONICAL_CURRENT_PRODUCER",
                        "CANONICAL_CURRENT_PRODUCER",
                        "CANDIDATE_ONLY",
                        "FLAG_ONLY",
                        "CANDIDATE_ONLY",
                        "TOP_LEVEL_ONLY",
                        "TOP_LEVEL_ONLY",
                        "TOP_LEVEL_ONLY",
                        "TOP_LEVEL_ONLY",
                        "MALFORMED_CANONICAL",
                        "MALFORMED_CANONICAL",
                        "MALFORMED_CANONICAL",
                        "MALFORMED_CANONICAL",
                        "MALFORMED_CANONICAL",
                        "MALFORMED_CANONICAL",
                        "PARTIAL_HISTORICAL",
                        "PARTIAL_HISTORICAL",
                        "MATCHING_REDUNDANT",
                        "CANDIDATE_ONLY",
                        "TOP_LEVEL_ONLY",
                        "MATCHING_REDUNDANT"
                ));
        assertThat(textValues(matrix.get("cases"), "sourceCurrency"))
                .contains("PLN", "USD", "EUR", "GBP", "JPY");
    }

    private void assertExpectedResultsGuard(JsonNode matrix) throws IOException, NoSuchAlgorithmException {
        JsonNode source = matrix.get("source");
        assertThat(source.get("compatibilityReviewMarker").textValue()).isEqualTo("FDP-129-COMPATIBILITY-REVIEW");
        assertThat(sha256(mapper.writeValueAsString(matrix.get("cases"))))
                .isEqualTo(source.get("expectedResultsSha256").textValue());
    }

    private String sha256(String value) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode matrix() throws IOException {
        return mapper.readTree(MATRIX.toFile());
    }

    private TransactionEnrichedEvent eventFrom(JsonNode compatibilityCase) {
        JsonNode topLevelFacts = compatibilityCase.get("topLevelFacts");
        JsonNode windows = compatibilityCase.get("windows");
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction().build();
        return new TransactionEnrichedEvent(
                "evt-" + caseId(compatibilityCase),
                "txn-" + caseId(compatibilityCase),
                "corr-" + caseId(compatibilityCase),
                base.customerId(),
                base.accountId(),
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:00Z"),
                new Money(decimal(topLevelFacts, "currentAmount", "100.00"), compatibilityCase.get("sourceCurrency").textValue()),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                integerOrNull(topLevelFacts, "recentTransactionCount"),
                textOrNull(windows, "topLevelCount"),
                moneyOrNull(topLevelFacts),
                textOrNull(windows, "topLevelAmount"),
                doubleOrNull(topLevelFacts, "transactionVelocityPerMinute"),
                base.merchantFrequency7d(),
                booleanValue(topLevelFacts, "deviceNovelty"),
                booleanValue(topLevelFacts, "countryMismatch"),
                booleanValue(topLevelFacts, "proxyOrVpn"),
                textValues(compatibilityCase.get("legacyFlags")),
                featureSnapshot(compatibilityCase)
        );
    }

    private Map<String, Object> featureSnapshot(JsonNode compatibilityCase) {
        JsonNode facts = compatibilityCase.get("canonicalFacts");
        JsonNode windows = compatibilityCase.get("windows");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putInteger(snapshot, facts, FraudFeatureContract.RECENT_TRANSACTION_COUNT, "recentTransactionCount");
        putDecimal(snapshot, facts, FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, "recentAmountSumPln");
        putInteger(snapshot, facts, FraudFeatureContract.RAPID_TRANSFER_COUNT, "rapidTransferCount");
        putDecimal(snapshot, facts, FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, "rapidTransferTotalPln");
        putDouble(snapshot, facts, FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, "transactionVelocityPerMinute");
        if (facts.has("malformedRecentTransactionCount")) {
            snapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, facts.get("malformedRecentTransactionCount").textValue());
        }
        if (facts.has("malformedRecentAmountSumPln")) {
            snapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, facts.get("malformedRecentAmountSumPln").textValue());
        }
        if (facts.has("malformedRapidTransferTotalPln")) {
            snapshot.put(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, facts.get("malformedRapidTransferTotalPln").textValue());
        }
        putText(snapshot, windows, FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "snapshotCount");
        putText(snapshot, windows, FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "snapshotAmount");
        putText(snapshot, windows, FraudFeatureContract.RAPID_TRANSFER_WINDOW, "snapshotRapid");
        if (compatibilityCase.has("legacyCandidate")) {
            snapshot.put(
                    FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE,
                    compatibilityCase.get("legacyCandidate").booleanValue()
            );
        }
        return Map.copyOf(snapshot);
    }

    private List<String> contributionKeys(Map<String, Object> scoreDetails) {
        return scoreDetails.keySet()
                .stream()
                .filter(key -> CONTRIBUTION_KEY_SUFFIXES.stream().anyMatch(key::endsWith))
                .toList();
    }

    private Money moneyOrNull(JsonNode topLevelFacts) {
        if (!topLevelFacts.has("recentAmountSum")) {
            return null;
        }
        return new Money(
                new BigDecimal(topLevelFacts.get("recentAmountSum").textValue()),
                topLevelFacts.get("recentAmountCurrency").textValue()
        );
    }

    private void putInteger(Map<String, Object> snapshot, JsonNode facts, String key, String field) {
        if (facts.has(field)) {
            snapshot.put(key, facts.get(field).intValue());
        }
    }

    private void putDouble(Map<String, Object> snapshot, JsonNode facts, String key, String field) {
        if (facts.has(field)) {
            snapshot.put(key, facts.get(field).doubleValue());
        }
    }

    private void putDecimal(Map<String, Object> snapshot, JsonNode facts, String key, String field) {
        if (facts.has(field)) {
            snapshot.put(key, new BigDecimal(facts.get(field).textValue()));
        }
    }

    private void putText(Map<String, Object> snapshot, JsonNode node, String key, String field) {
        if (node.has(field)) {
            snapshot.put(key, node.get(field).textValue());
        }
    }

    private BigDecimal decimal(JsonNode node, String field, String defaultValue) {
        return new BigDecimal(node.has(field) ? node.get(field).textValue() : defaultValue);
    }

    private Integer integerOrNull(JsonNode node, String field) {
        return node.has(field) ? node.get(field).intValue() : null;
    }

    private Double doubleOrNull(JsonNode node, String field) {
        return node.has(field) ? node.get(field).doubleValue() : null;
    }

    private boolean booleanValue(JsonNode node, String field) {
        return node.has(field) && node.get(field).booleanValue();
    }

    private String textOrNull(JsonNode node, String field) {
        return node.has(field) ? node.get(field).textValue() : null;
    }

    private String caseId(JsonNode compatibilityCase) {
        return compatibilityCase.get("caseId").textValue();
    }

    private List<String> textValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null) {
            node.forEach(item -> values.add(item.textValue()));
        }
        return List.copyOf(values);
    }

    private List<String> textValues(JsonNode cases, String field) {
        return StreamSupport.stream(cases.spliterator(), false)
                .map(item -> item.get(field).textValue())
                .toList();
    }
}
