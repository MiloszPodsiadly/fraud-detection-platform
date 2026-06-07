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
        String normalizedArchitecture = architecture.replaceAll("\\s+", " ");

        assertThat(product)
                .contains("no runtime scoring behavior changes")
                .contains("does not")
                .doesNotContain("guaranteed fraud proof");
        assertThat(architecture)
                .contains("maintains only the shared engine-result contract")
                .contains("does not add `engineResults[]` to `TransactionScoredEvent`")
                .contains("Consumers tolerate unknown additive fields")
                .contains("Breaking semantic changes require versioning")
                .contains("Maximum items")
                .contains("safe bounded summaries only");
        assertThat(normalizedArchitecture)
                .contains("They do not replace, extend, or project the existing `ScoringEvidenceItem`")
                .contains("they are not silently promoted into the existing platform `ReasonCode` taxonomy")
                .contains("`FraudEngineEvidence.source` is a bounded uppercase machine-readable origin code")
                .contains("Only `FALLBACK_USED` declares that an actual fallback occurred")
                .contains("Validation in this contract blocks obvious unsafe content only. It is not DLP")
                .contains("sanitize explanation summaries before constructing `FraudEngineResult`")
                .contains("a `statusReason` mapping table");
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
