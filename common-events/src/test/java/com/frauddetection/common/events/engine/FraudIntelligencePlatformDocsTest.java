package com.frauddetection.common.events.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FraudIntelligencePlatformDocsTest {

    private static final List<String> REQUIRED_DOCS = List.of(
            "product/fraud_intelligence_platform.md",
            "product/fraud_intelligence_non_goals.md",
            "product/fraud_intelligence_glossary.md",
            "events/fraud_engine_result_contract.md",
            "architecture/multi_engine_scoring_architecture.md"
    );

    @Test
    void productDocsExistAndMaintainTheAnalystAssistedBoundary() throws Exception {
        StringBuilder content = new StringBuilder();
        for (String relativePath : REQUIRED_DOCS) {
            Path path = docsRoot().resolve(relativePath);
            assertThat(path).as(relativePath).exists();
            content.append(Files.readString(path)).append('\n');
        }

        String docs = content.toString().toLowerCase(Locale.ROOT);
        assertThat(docs)
                .contains("fraud intelligence platform")
                .contains("analyst-assisted")
                .contains("multi-engine")
                .contains("rule engine")
                .contains("python ml engine")
                .contains("feedback")
                .contains("no automatic decline")
                .contains("no automatic approve")
                .contains("no core banking authorization")
                .contains("no final payment decision")
                .contains("engine result is not a final banking decision")
                .contains("ml is not a final decision source");
    }

    @Test
    void docsDenyOverclaimsAndDoNotDescribeRuntimeIntegration() throws Exception {
        String product = Files.readString(docsRoot().resolve("product/fraud_intelligence_platform.md"));
        String architecture = Files.readString(docsRoot().resolve("architecture/multi_engine_scoring_architecture.md"));
        String nonGoals = Files.readString(docsRoot().resolve("product/fraud_intelligence_non_goals.md"));
        String contract = Files.readString(docsRoot().resolve("events/fraud_engine_result_contract.md"));
        String glossary = Files.readString(docsRoot().resolve("product/fraud_intelligence_glossary.md"));
        String normalizedArchitecture = architecture.replaceAll("\\s+", " ");
        String normalizedDocs = (architecture + "\n" + contract + "\n" + glossary).replaceAll("\\s+", " ");
        String docsLower = normalizedDocs.toLowerCase(Locale.ROOT);

        assertThat(product)
                .contains("no runtime scoring behavior changes")
                .contains("does not")
                .doesNotContain("guaranteed fraud proof");
        assertThat(architecture)
                .contains("maintains only the shared engine-result contract")
                .contains("does not add `engineResults[]` to `TransactionScoredEvent`")
                .contains("Consumers tolerate unknown additive fields")
                .contains("Breaking semantic changes require versioning")
                .contains("Maximum items");
        assertThat(normalizedArchitecture)
                .contains("They do not replace, extend, or project the existing `ScoringEvidenceItem`")
                .contains("they are not silently promoted into the existing platform `ReasonCode` taxonomy")
                .contains("`FraudEngineEvidence.source` is a bounded uppercase machine-readable origin code")
                .contains("Only `FALLBACK_USED` declares that an actual fallback occurred")
                .contains("Validation in this contract blocks obvious unsafe content only. It is not DLP")
                .contains("safe bounded summaries only")
                .contains("sanitize explanation summaries before constructing `FraudEngineResult`")
                .contains("a `statusReason` mapping table");
        assertThat(normalizedDocs)
                .contains("diagnostic only")
                .contains("not platform aggregation")
                .contains("platform probability or decision signal")
                .contains("The Java contract retains `Double` for compatibility")
                .contains("JSON producers must emit finite values")
                .contains("`statusReason` is the canonical serialized JSON field")
                .contains("`fallbackReason` is accepted only as a JSON input alias")
                .contains("is not serialized as output")
                .contains("`AVAILABLE`")
                .contains("`LOW`, `MEDIUM`, or `HIGH` required")
                .contains("Contribution `direction` is the semantic source of truth")
                .contains("Evidence `title` and `description` are bounded display summaries only")
                .contains("It is not DLP")
                .contains("does not introduce `ScoringContext`, `FraudSignalEngine`")
                .contains("model retraining")
                .contains("rule updates");
        assertThat(docsLower)
                .contains("not a final banking decision")
                .contains("not automatic blocking")
                .contains("not core banking authorization")
                .contains("does not add new `scoringcontext`")
                .contains("api")
                .contains("ui")
                .contains("dataset export")
                .contains("feedback dataset")
                .contains("payment authorization")
                .contains("analyst recommendation")
                .doesNotContain("fdp-82");
        assertThat(nonGoals)
                .contains("no bank-certified production decision claim")
                .doesNotContain("auto-decline production");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("docs");
    }
}
