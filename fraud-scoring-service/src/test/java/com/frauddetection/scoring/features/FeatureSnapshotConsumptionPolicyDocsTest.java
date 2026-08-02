package com.frauddetection.scoring.features;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureSnapshotConsumptionPolicyDocsTest {

    @Test
    void documentsCurrentFeatureSnapshotPolicyWithoutStaleHistoricalRuntimeClaims() throws Exception {
        String document = Files.readString(docsRoot().resolve("architecture/feature_snapshot_consumption_policy.md"));
        String docs = document.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("feature snapshot consumption policy")
                .contains("current feature-snapshot consumption policy")
                .contains("historical fdp-85 scope")
                .contains("current fdp-129 runtime architecture")
                .contains("scoringcontext.featuresnapshot")
                .contains("fraudsignalengine")
                .contains("diagnostic multi-engine runtime")
                .contains("rules, ml, and optional velocity execute through")
                .contains("current orchestrator path")
                .contains("transactionscoredevent.engineintelligence")
                .contains("featureSnapshot` is transported inside controlled internal kafka events".toLowerCase(Locale.ROOT))
                .contains("not the public analyst/api engine intelligence payload")
                .contains("common-events` owns the event wire boundary")
                .contains("featuresnapshotwirevaluenormalizer")
                .contains("featuresnapshotwirevaluedeserializer")
                .contains("adapters consume the normalized snapshot through `scoringcontext` and `featuresnapshotreader`")
                .contains("not by reparsing json")
                .contains("present")
                .contains("missing")
                .contains("invalid_type")
                .contains("wrong_accessor")
                .contains("not_allowed")
                .contains("missing boolean is not false")
                .contains("missing number is not zero")
                .contains("missing string is not an empty string")
                .contains("invalid type is not coerced")
                .contains("top-level null keys are invalid")
                .contains("top-level null values are invalid")
                .contains("arbitrary nested structures are not consumed")
                .contains("raw payloads")
                .contains("tokens")
                .contains("secrets")
                .contains("stack traces")
                .contains("exception text")
                .contains("pan/card or account identifiers")
                .contains("canonical feature keys")
                .contains("camelcase")
                .contains("adapter consumption is not key-only")
                .contains("key and expected scalar type")
                .contains("a registered key is not automatically adapter-consumable")
                .contains("not consumable by the v1 scalar reader")
                .contains("devicenovelty` is boolean")
                .contains("recenttransactioncount` is integer")
                .contains("transactionvelocityperminute` is double")
                .contains("currency` is string")
                .contains("rapidtransfertotalpln` is decimal")
                .contains("rapidtransfertransactionids` is not consumable by the v1 scalar reader")
                .contains("featureflags` is not consumable by the v1 scalar reader")
                .contains("wrong accessor use is not valid consumption")
                .contains("wrong_accessor` means")
                .contains("invalid_type` means")
                .contains("runtime value type")
                .contains("not_allowed` and exception messages must not expose raw rejected keys")
                .contains("`isallowedfeaturekey` is not adapter-consumption permission")
                .contains("adapters must still use `featuresnapshotreader` or `expectedtypefor`")
                .contains("the key is known and safe enough for policy evaluation")
                .contains("customersegment")
                .contains("merchantcategory")
                .contains("reading a string feature internally does not authorize exposing the raw value")
                .contains("evidence and privacy restrictions")
                .contains("fraudengineresult` evidence")
                .contains("logs")
                .contains("metrics")
                .contains("ui")
                .contains("velocity pt1m policy")
                .contains("velocity remains optional, diagnostic-only")
                .contains("rules canonical input policy")
                .contains("present-invalid canonical rules inputs fail closed")
                .contains("must not become `available low`")
                .contains("must not fall back to legacy flags")
                .contains("retained compatibility")
                .contains("engineintelligencecomparisonv1compatibility")
                .contains("old-event `engineintelligence == null` handling")
                .contains("kafka and mongo replay support")
                .contains("legacy retirement preconditions")
                .contains("removal only after zero-use evidence")
                .contains("casting raw `map<string, object>` values")
                .contains("context.featuresnapshot().get(...)");

        assertThat(docs)
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
                .doesNotContain("current runtime still contains no `fraudscoringorchestrator`")
                .doesNotContain("current runtime still contains no fraudscoringorchestrator")
                .doesNotContain("no `engineresults[]`, and no event/api/ui integration")
                .doesNotContain("featuresnapshot` remains internal to `fraud-scoring-service`. it is not a kafka event")
                .doesNotContain("this branch introduces no runtime scoring behavior change, no event/api/ui change")
                .doesNotContain("compatibility code is a solid violation");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
