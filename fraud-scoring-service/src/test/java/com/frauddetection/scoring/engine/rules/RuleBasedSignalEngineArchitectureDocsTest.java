package com.frauddetection.scoring.engine.rules;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedSignalEngineArchitectureDocsTest {

    @Test
    void documentsAdapterOnlyBoundaryAndSafetyRules() throws Exception {
        String document = Files.readString(docsRoot().resolve("architecture/rule_based_signal_engine_adapter.md"));
        String docs = document.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("fdp-87 adapter foundation only")
                .contains("rulebasedsignalengine")
                .contains("fraudsignalengine")
                .contains("scoringcontext")
                .contains("featuresnapshotreader")
                .contains("fraudengineresult")
                .contains("internal to `fraud-scoring-service`")
                .contains("not a spring component")
                .contains("not wired into `compositefraudscoringengine`")
                .contains("not a production runtime path")
                .contains("existing `rulebasedfraudscoringengine` remains production source of truth")
                .contains("true adapter around `rulebasedfraudscoringengine`")
                .contains("delegates scoring to the production rule engine")
                .contains("must not keep independent weights")
                .contains("high thresholds")
                .contains("critical thresholds")
                .contains("local score calculations")
                .contains("no runtime scoring behavior changes")
                .contains("no orchestrator")
                .contains("no python ml adapter")
                .contains("no event/api/ui/projection changes")
                .contains("no `engineresults[]`")
                .contains("must use `featuresnapshotreader`")
                .contains("must not call `context.featuresnapshot().get")
                .contains("must not cast raw `map<string, object>`")
                .contains("must not use `featuresnapshotkeypolicy.isallowedfeaturekey` as permission")
                .contains("preflight is intentionally limited")
                .contains("snapshot keys that production rule engine actually consumes")
                .contains("must not degrade based on invalid snapshot values for typed event fields")
                .contains("avoids semantic drift")
                .contains("does not refactor production `rulebasedfraudscoringengine`")
                .contains("present")
                .contains("missing")
                .contains("invalid_type")
                .contains("wrong_accessor")
                .contains("not_allowed")
                .contains("fail fast")
                .contains("scoringcontext.receivedat()")
                .contains("latencyms")
                .contains("is `0`")
                .contains("not false")
                .contains("not zero")
                .contains("not low risk")
                .contains("bounded reason codes")
                .contains("raw feature values")
                .contains("customersegment raw value")
                .contains("merchantcategory raw value")
                .contains("currency raw value")
                .contains("amount raw values")
                .contains("transaction ids");

        assertThat(docs)
                .doesNotContain("adapter is production scoring source")
                .doesNotContain("orchestrator is included")
                .doesNotContain("pythonmlsignalengine is included")
                .doesNotContain("event schema changed")
                .doesNotContain("api/ui changed")
                .doesNotContain("automatic approve is included")
                .doesNotContain("automatic decline is included")
                .doesNotContain("includes final banking decisioning")
                .doesNotContain("ml final decision source");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
