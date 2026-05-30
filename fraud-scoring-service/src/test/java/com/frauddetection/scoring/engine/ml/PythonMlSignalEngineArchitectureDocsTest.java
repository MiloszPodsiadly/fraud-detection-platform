package com.frauddetection.scoring.engine.ml;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PythonMlSignalEngineArchitectureDocsTest {

    @Test
    void documentsAdapterOnlyBoundaryAndSafetyRules() throws Exception {
        String document = Files.readString(docsRoot().resolve("architecture/python_ml_signal_engine_adapter.md"));
        String docs = document.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("fdp-88 adapter foundation only")
                .contains("pythonmlsignalengine")
                .contains("fraudsignalengine")
                .contains("scoringcontext")
                .contains("fraudengineresult")
                .contains("existing ml scoring boundary remains source of truth")
                .contains("internal to `fraud-scoring-service`")
                .contains("not a spring component")
                .contains("not wired into `compositefraudscoringengine`")
                .contains("no runtime scoring behavior changes")
                .contains("no orchestrator")
                .contains("no event/api/ui/projection changes")
                .contains("no `engineresults[]`")
                .contains("ml is not final decision source")
                .contains("ml unavailable is not low risk")
                .contains("ml timeout is not low risk")
                .contains("ml invalid response is not available")
                .contains("missing score is degraded")
                .contains("score out of range is degraded")
                .contains("bounded ml reason codes")
                .contains("no raw model payload")
                .contains("no raw feature vector")
                .contains("request body")
                .contains("response body")
                .contains("stacktrace")
                .contains("endpoint url")
                .contains("host/token/secret")
                .contains("customer/account/card identifiers");

        assertThat(docs)
                .doesNotContain("adapter is production scoring path")
                .doesNotContain("orchestrator is included")
                .doesNotContain("event schema changed")
                .doesNotContain("api/ui changed")
                .doesNotContain("automatic approve")
                .doesNotContain("automatic decline")
                .doesNotContain("final banking decisioning")
                .doesNotContain("ml final decision source")
                .doesNotContain("unavailable means low risk")
                .doesNotContain("timeout means low risk")
                .doesNotContain("raw ml evidence allowed");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
