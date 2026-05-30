package com.frauddetection.scoring.engine.ml;

import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.context;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.flatten;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.sourceReturning;
import static com.frauddetection.scoring.engine.ml.PythonMlSignalEngineTestSupport.sourceThrowing;
import static org.assertj.core.api.Assertions.assertThat;

class PythonMlSignalEngineEvidenceSafetyTest {

    @Test
    void resultExposesOnlyBoundedMlReasonCodesAndSafeIdentifiers() {
        var result = new PythonMlSignalEngine(
                sourceReturning(PythonMlSignalEngineTestSupport.resultWithUnsafeDiagnostics())
        ).evaluate(context());

        assertNoUnsafeMlData(result);
        Set<String> allowedReasonCodes = allowedReasonCodes();
        assertThat(result.reasonCodes()).isNotEmpty().allSatisfy(reasonCode ->
                assertThat(allowedReasonCodes).contains(reasonCode)
        );
        assertThat(result.evidence()).allSatisfy(evidence -> assertSafeEvidenceText(evidence, allowedReasonCodes));
        assertThat(result.contributions()).allSatisfy(contribution -> {
            assertThat(allowedReasonCodes).contains(contribution.feature());
            assertThat(contribution.value()).isNull();
            assertThat(contribution.weight()).isNull();
        });
    }

    @Test
    void thrownUnsafeMlDetailsAreNotExposed() {
        var result = new PythonMlSignalEngine(sourceThrowing(new IllegalStateException(
                "VIP crypto EUR 50000 tx-secret acct-secret http://ml-internal token stacktrace raw JSON PythonException"
        ))).evaluate(context());

        assertNoUnsafeMlData(result);
    }

    @Test
    void sourceScoringEvidenceIsNotCopiedDirectly() {
        var result = new PythonMlSignalEngine(
                sourceReturning(PythonMlSignalEngineTestSupport.resultWithUnsafeScoringEvidence())
        ).evaluate(context());

        assertThat(result.evidence()).extracting(FraudEngineEvidence::reasonCode)
                .containsExactly(ReasonCode.MODEL_HIGH_RISK.wireValue());
        assertThat(flatten(result))
                .doesNotContain("raw source scoring evidence")
                .doesNotContain("rawFeatureVector=VIP")
                .doesNotContain("http://ml-internal")
                .doesNotContain("token")
                .doesNotContain("stacktrace");
    }

    private void assertSafeEvidenceText(FraudEngineEvidence evidence, Set<String> allowedReasonCodes) {
        assertThat(allowedReasonCodes).contains(evidence.reasonCode());
        assertThat(evidence.title()).hasSizeLessThanOrEqualTo(64);
        assertThat(evidence.description()).hasSizeLessThanOrEqualTo(80);
        assertThat(evidence.title()).doesNotMatch(".*\\s{2,}.*");
        assertThat(evidence.description()).doesNotMatch(".*\\s{2,}.*");
    }

    private void assertNoUnsafeMlData(com.frauddetection.common.events.engine.FraudEngineResult result) {
        assertThat(flatten(result))
                .doesNotContain("VIP")
                .doesNotContain("crypto")
                .doesNotContain("EUR")
                .doesNotContain("50000")
                .doesNotContain("tx-secret")
                .doesNotContain("acct-secret")
                .doesNotContain("http://ml-internal")
                .doesNotContain("host")
                .doesNotContain("token")
                .doesNotContain("stacktrace")
                .doesNotContain("raw JSON")
                .doesNotContain("PythonException")
                .doesNotContain("feature vector")
                .doesNotContain("rawFeatureVector")
                .doesNotContain("rawModelPayload");
    }

    private Set<String> allowedReasonCodes() {
        Set<String> allowed = Arrays.stream(PythonMlSignalReasonCode.values())
                .map(PythonMlSignalReasonCode::wireValue)
                .collect(Collectors.toCollection(HashSet::new));
        Arrays.stream(ReasonCode.values())
                .filter(reasonCode -> reasonCode != ReasonCode.UNKNOWN)
                .map(ReasonCode::wireValue)
                .forEach(allowed::add);
        return allowed;
    }
}
