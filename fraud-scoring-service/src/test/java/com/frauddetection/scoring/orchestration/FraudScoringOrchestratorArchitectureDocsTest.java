package com.frauddetection.scoring.orchestration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorArchitectureDocsTest {

    @Test
    void documentsInternalOrchestrationBoundary() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/fraud_scoring_orchestrator.md"))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("fdp-89")
                .contains("internal-only orchestration foundation")
                .contains("fraudscoringorchestrator")
                .contains("fraudscoringorchestrationresult")
                .contains("fraudsignalengine")
                .contains("fraudengineresult")
                .contains("rulebasedsignalengine")
                .contains("pythonmlsignalengine")
                .contains("deterministic order")
                .contains("rules.primary")
                .contains("ml.python.primary")
                .contains("engine_registry_required_engine_missing")
                .contains("engine_registry_expected_engine_missing")
                .contains("fraudscoringorchestrationstatus")
                .contains("required_engine_failed")
                .contains("does not change `transactionscoredevent`")
                .contains("does not add `engineresults[]`")
                .contains("no kafka event schema change")
                .contains("no alert-service projection")
                .contains("no api/ui")
                .contains("no final decisioning")
                .contains("not a production runtime migration")
                .contains("does not replace `compositefraudscoringengine`")
                .contains("no approve/decline")
                .contains("unavailable is not low risk")
                .contains("timeout is not low risk")
                .contains("bounded per-engine failure result")
                .contains("no raw exception messages")
                .contains("does not enforce engine execution deadlines")
                .contains("a hanging engine can still block the caller")
                .contains("only preserves `timeout` statuses returned by adapters")
                .contains("timeout enforcement belongs to fdp-90")
                .contains("do not claim production resilience from fdp-89");

        assertThat(docs)
                .doesNotContain("event schema changed")
                .doesNotContain("api exposed")
                .doesNotContain("ui exposed")
                .doesNotContain("alert projection added")
                .doesNotContain("final decision source changed")
                .doesNotContain("ml final decision source")
                .doesNotContain("engine results published externally");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
