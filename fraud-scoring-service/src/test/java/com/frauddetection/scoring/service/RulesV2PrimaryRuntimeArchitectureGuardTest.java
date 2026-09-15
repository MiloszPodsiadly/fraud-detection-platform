package com.frauddetection.scoring.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RulesV2PrimaryRuntimeArchitectureGuardTest {

    private static final Map<String, String> PRIMARY_RUNTIME_SOURCES = Map.of(
            "RuleBasedFraudScoringEngine", "src/main/java/com/frauddetection/scoring/service/RuleBasedFraudScoringEngine.java",
            "RuleBasedSignalEngine", "src/main/java/com/frauddetection/scoring/engine/rules/RuleBasedSignalEngine.java",
            "CompositeFraudScoringEngine", "src/main/java/com/frauddetection/scoring/service/CompositeFraudScoringEngine.java",
            "EngineIntelligenceRuntimeConfig", "src/main/java/com/frauddetection/scoring/config/EngineIntelligenceRuntimeConfig.java"
    );

    @Test
    void primaryRulesRuntimeUsesV2ValidationAndPolicyWithoutV1Fallback() throws Exception {
        String scoringEngine = source("RuleBasedFraudScoringEngine");

        assertThat(scoringEngine)
                .contains(
                        "private final RulesScoringPolicyV2 rulesV2Policy;",
                        "RulesV2InputValidator.requireValidInput(request.event())",
                        "scoreValidated(RulesV2ValidatedInput input)"
                )
                .doesNotContain(
                        "RulesScoringPolicyV1",
                        "RulesV1CompatibilityResolver",
                        "RulesFeatureInputValidator",
                        "ValidatedRulesInput",
                        "try {",
                        "catch ("
                );
    }

    @Test
    void diagnosticRulesAdapterCallsSameV2SourceOfTruth() throws Exception {
        String adapter = source("RuleBasedSignalEngine");

        assertThat(adapter)
                .contains(
                        "private static final String ENGINE_ID = FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID;",
                        "private static final String ENGINE_VERSION = \"2.0.0\";",
                        "RulesV2InputValidator.validate(reader)",
                        "RulesV2InputValidator.requireValidInput(reader)",
                        "productionRuleEngine.scoreValidated(input)"
                )
                .doesNotContain(
                        "RulesFeatureInputValidator",
                        "ValidatedRulesInput",
                        "RulesScoringPolicyV1",
                        "RulesV1CompatibilityResolver",
                        "LEGACY_FLAG",
                        "TOP_LEVEL_COMPATIBILITY",
                        "LEGACY_DERIVED_CANDIDATE"
                );
    }

    @Test
    void activePrimaryWiringDoesNotReachV1CompatibilityClasses() throws Exception {
        for (String source : PRIMARY_RUNTIME_SOURCES.keySet()) {
            assertThat(source(source))
                    .as(source)
                    .doesNotContain(
                            "RulesScoringPolicyV1",
                            "RulesV1CompatibilityResolver",
                            "RulesFeatureInputValidator",
                            "RulesV1Contribution",
                            "RulesV1SignalResolution",
                            "RulesV1ShadowScoringEngine",
                            "RulesV2ShadowComparator",
                            "LEGACY_FLAG",
                            "TOP_LEVEL_COMPATIBILITY",
                            "LEGACY_DERIVED_CANDIDATE"
                    );
        }
    }

    @Test
    void productionRulesScoringSourceContainsNoExecutableV1OrLegacyFallbackPath() throws Exception {
        String productionSource = sourceFiles(moduleRoot().resolve("src/main/java/com/frauddetection/scoring"));

        assertThat(productionSource)
                .doesNotContain(
                        "RulesScoringPolicyV1",
                        "RulesV1CompatibilityResolver",
                        "RulesFeatureInputValidator",
                        "ValidatedRulesInput",
                        "RulesV1Contribution",
                        "RulesV1ContributionSource",
                        "RulesV1SignalResolution",
                        "PredicateResolution",
                        "RulesV1ShadowScoringEngine",
                        "RulesV2ShadowComparator",
                        "RulesV2ShadowComparison",
                        "RulesV2ShadowDiffCategory",
                        "LEGACY_FLAG",
                        "TOP_LEVEL_COMPATIBILITY",
                        "LEGACY_DERIVED_CANDIDATE",
                        "isRulesV1CanonicalWindowText",
                        "scoreValidated(FraudScoringRequest request, RulesInputValidationResult validation)"
                );
    }

    @Test
    void productionRulesRuntimeHasExactlyOneAuthoritativeV2DecisionPath() throws Exception {
        String productionSource = sourceFiles(moduleRoot().resolve("src/main/java/com/frauddetection/scoring"));
        String scoringEngine = source("RuleBasedFraudScoringEngine");

        assertThat(countOccurrences(productionSource, "new RulesScoringPolicyV2("))
                .isEqualTo(1);
        assertThat(countOccurrences(productionSource, "public FraudScoreResult scoreValidated(RulesV2ValidatedInput input)"))
                .isEqualTo(1);
        assertThat(countOccurrences(productionSource, "scoreValidated(FraudScoringRequest"))
                .isZero();
        assertThat(scoringEngine)
                .contains(
                        "return withFeatureSnapshot(scoreValidated(input), request.featureSnapshot());",
                        "return rulesV2Policy.score(input);"
                )
                .doesNotContain("new RuleBasedFraudScoringEngine(");
        assertThat(countOccurrences(scoringEngine, "new RulesScoringPolicyV2("))
                .isEqualTo(1);
        assertThat(source("RuleBasedSignalEngine"))
                .contains("productionRuleEngine.scoreValidated(input)")
                .doesNotContain("new RulesScoringPolicyV2(");
    }

    @Test
    void commonFeatureContractsDoNotExposeRulesV1CanonicalWindowAliases() throws Exception {
        String source = sourceFiles(moduleRoot().resolve("../common-events/src/main/java/com/frauddetection/common/events/features"));

        assertThat(source)
                .doesNotContain(
                        "RULES_V1_CANONICAL_WINDOW",
                        "RULES_V1_CANONICAL_WINDOW_TEXT",
                        "isRulesV1CanonicalWindowText"
                )
                .contains("CANONICAL_RECENT_TRANSACTION_WINDOW");
    }

    private String source(String name) throws Exception {
        return Files.readString(moduleRoot().resolve(PRIMARY_RUNTIME_SOURCES.get(name)));
    }

    private Path moduleRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("src/main"))
                ? current
                : current.resolve("fraud-scoring-service");
    }

    private String sourceFiles(Path root) throws Exception {
        if (!Files.exists(root)) {
            return "";
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        StringBuilder source = new StringBuilder();
        for (Path file : files) {
            source.append(Files.readString(file)).append('\n');
        }
        return source.toString();
    }

    private long countOccurrences(String source, String token) {
        long count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }
}
