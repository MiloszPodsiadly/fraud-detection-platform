package com.frauddetection.scoring.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FraudSignalEngineArchitectureDocsTest {

    @Test
    void documentsInternalInterfaceBoundaryAndRejectedIntegrationClaims() throws Exception {
        String document = Files.readString(docsRoot().resolve("architecture/fraud_signal_engine_boundary.md"));
        String docs = document.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("fraudsignalengine")
                .contains("fraudenginedescriptor")
                .contains("scoringcontext")
                .contains("fraudengineresult")
                .contains("internal runtime interface")
                .contains("static engine identity/capability metadata")
                .contains("fraud-scoring-service")
                .contains("not a `common-events` contract")
                .contains("not a kafka event")
                .contains("not an api dto")
                .contains("not an orchestrator")
                .contains("not a decisioning mechanism")
                .contains("not a final banking decision source")
                .contains("no runtime scoring behavior change")
                .contains("canonical lowercase allowlisted implementation language")
                .contains("aliases are rejected instead of normalized")
                .contains("`cpp` instead of `c++`")
                .contains("`csharp` instead of `c#`")
                .contains("`javascript` instead of `js`/`node`/`nodejs`")
                .contains("`typescript` instead of `ts`")
                .contains("`required` is descriptive only")
                .contains("no runtime fallback semantics")
                .contains("no routing semantics")
                .contains("no decisioning semantics")
                .contains("featuresnapshot consumption policy must be defined before adapters")
                .contains("no `rulebasedsignalengine`")
                .contains("`pythonmlsignalengine`")
                .contains("`fraudscoringorchestrator`")
                .contains("no `engineresults[]`")
                .contains("no api/ui");

        assertThat(docs)
                .doesNotContain("automatic approve")
                .doesNotContain("automatic decline")
                .doesNotContain("transaction blocking")
                .doesNotContain("ml final decision source")
                .doesNotContain("core banking authorization")
                .doesNotContain("public api contract")
                .doesNotContain("kafka event contract")
                .doesNotContain("engine wrappers are included")
                .doesNotContain("orchestrator is included");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
