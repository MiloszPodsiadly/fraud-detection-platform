package com.frauddetection.scoring.features;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureSnapshotConsumptionPolicyDocsTest {

    @Test
    void documentsInternalReaderPolicyWithoutClaimingRuntimeIntegration() throws Exception {
        String document = Files.readString(docsRoot().resolve("architecture/feature_snapshot_consumption_policy.md"));
        String docs = document.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("feature snapshot consumption policy")
                .contains("fdp-85 internal adapter-consumption policy only")
                .contains("scoringcontext.featuresnapshot")
                .contains("fraudsignalengine")
                .contains("future adapters")
                .contains("featuresnapshot` remains internal")
                .contains("not a kafka event")
                .contains("not an api dto")
                .contains("not a storage document")
                .contains("not a public cross-service contract")
                .contains("not a source of truth")
                .contains("does not compute features")
                .contains("does not score risk")
                .contains("present")
                .contains("missing")
                .contains("invalid_type")
                .contains("not_allowed")
                .contains("missing boolean is not false")
                .contains("missing number is not zero")
                .contains("missing string is not empty string")
                .contains("invalid type is not coerced")
                .contains("top-level null keys are invalid")
                .contains("top-level null values are invalid")
                .contains("arbitrary nested structures are not consumed")
                .contains("raw payloads")
                .contains("tokens/secrets")
                .contains("stack traces/exception text")
                .contains("pan/card/account identifiers")
                .contains("canonical feature keys")
                .contains("camelcase")
                .contains("adapter consumption is not key-only")
                .contains("key and expected scalar type")
                .contains("registered `fraudfeaturecontract` key does not automatically mean scalar adapter-consumable")
                .contains("some registered keys are intentionally not consumable by the v1 scalar reader")
                .contains("devicenovelty` is boolean")
                .contains("recenttransactioncount` is integer")
                .contains("transactionvelocityperminute` is double")
                .contains("currency` is string")
                .contains("rapidtransfertotalpln` is decimal")
                .contains("rapidtransfertransactionids` is not consumable by v1 scalar reader")
                .contains("featureflags` is not consumable by v1 scalar reader")
                .contains("wrong accessor is not valid consumption")
                .contains("not_allowed` results must not expose raw rejected keys")
                .contains("featuresnapshotreader` accepts the existing internal snapshot shape")
                .contains("consumption is controlled at read time")
                .contains("no `rulebasedsignalengine`")
                .contains("no `pythonmlsignalengine`")
                .contains("no `fraudscoringorchestrator`")
                .contains("no `engineresults[]`")
                .contains("no event/api/ui")
                .contains("fdp-86 may add `rulebasedsignalengine` only after it uses the policy/accessor")
                .contains("must not directly cast values from `map<string, object>`")
                .contains("must not directly")
                .contains("context.featuresnapshot().get(...)");

        assertThat(docs)
                .doesNotContain("adapters are included")
                .doesNotContain("orchestrator is included")
                .doesNotContain("automatic approve")
                .doesNotContain("automatic decline")
                .doesNotContain("transaction blocking")
                .doesNotContain("ml final decision source")
                .doesNotContain("missing means false")
                .doesNotContain("missing means zero")
                .doesNotContain("type coercion is allowed")
                .doesNotContain("raw payload consumption is allowed")
                .doesNotContain("registered key automatically consumable")
                .doesNotContain("raw rejected key is returned")
                .doesNotContain("runtime scoring behavior is changed");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
