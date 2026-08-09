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
                .contains("historical fdp-87 adapter foundation")
                .contains("superseded for fdp-129 runtime semantics")
                .contains("rulebasedsignalengine")
                .contains("fraudsignalengine")
                .contains("scoringcontext")
                .contains("featuresnapshotreader")
                .contains("fraudsignalevaluation")
                .contains("fraudscoringorchestrator")
                .contains("diagnostic engine intelligence runtime")
                .contains("existing `rulebasedfraudscoringengine` remains the primary production scoring source of truth")
                .contains("true adapter around `rulebasedfraudscoringengine`")
                .contains("delegates scoring to the production rule engine")
                .contains("must not keep independent weights")
                .contains("high thresholds")
                .contains("critical thresholds")
                .contains("local score calculations")
                .contains("fdp-129 supersedes that historical non-goal")
                .contains("must not be used to claim that those fdp-129 runtime components are absent")
                .contains("must use `featuresnapshotreader`")
                .contains("must not call `context.featuresnapshot().get")
                .contains("must not cast raw `map<string, object>`")
                .contains("must not use `featuresnapshotkeypolicy.isallowedfeaturekey` as permission")
                .contains("fdp-129 preflight covers the canonical rules v1 snapshot facts")
                .contains("primary scoring failure and diagnostic adapter degradation are intentionally different runtime boundaries")
                .contains("present")
                .contains("missing")
                .contains("invalid_type")
                .contains("wrong_accessor")
                .contains("not_allowed")
                .contains("fail fast")
                .contains("publishes bounded engine-result `generatedat` and `latencyms`")
                .contains("injected execution `clock`")
                .contains("latencyms")
                .contains("not false")
                .contains("not zero")
                .contains("not low risk")
                .contains("bounded reason codes")
                .contains("raw feature values")
                .contains("customersegment raw value")
                .contains("merchantcategory raw value")
                .contains("currency raw value")
                .contains("amount raw values")
                .contains("transaction ids")
                .contains("no fake scored event is fabricated")
                .contains("eligible ml diagnostics can still execute");

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
