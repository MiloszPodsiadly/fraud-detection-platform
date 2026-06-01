package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudEngineEvidenceSanitizerTest {
    private final FraudEngineEvidenceSanitizer sanitizer = new FraudEngineEvidenceSanitizer();

    @Test
    void truncatesCountAndTextDeterministically() {
        FraudEngineAggregationPolicy strict = new FraudEngineAggregationPolicy(2, 10, 1, 5, 5, 20, 128, 10, 12);
        List<FraudEngineEvidence> source = List.of(
                evidence("MODEL_HIGH_RISK", "A safe bounded title", "A safe bounded description"),
                evidence("LOW_MODEL_RISK", "Second title", "Second description")
        );
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize("ml.python.primary", source, strict, warnings);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).hasSize(10);
        assertThat(result.getFirst().description()).hasSize(12);
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .contains(
                        FraudEngineAggregationWarningCode.EVIDENCE_TEXT_TRUNCATED,
                        FraudEngineAggregationWarningCode.EVIDENCE_LIMIT_APPLIED
                );
    }

    @Test
    void dropsUnsafeEvidenceWithoutLeakingRawText() {
        List<String> forbidden = List.of(
                "transactionId.raw",
                "customerId.raw",
                "accountId.raw",
                "cardId.raw",
                "merchantId.raw",
                "endpoint.raw",
                "token.raw",
                "secret.raw",
                "stacktrace.raw",
                "rawFeatureVector.raw"
        );
        List<FraudEngineEvidence> source = forbidden.stream()
                .map(reason -> evidence(reason, "Safe title", "Safe description"))
                .toList();
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "rules.primary",
                source,
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).isEmpty();
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .contains(FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED);
        assertThat(result.toString()).doesNotContain("transactionId", "accountId", "endpoint", "token", "stacktrace");
    }

    @Test
    void dropsEvidenceWithUnsupportedSafeReasonCodeUsingUnsupportedWarning() {
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "ml.python.primary",
                List.of(evidence("UNSUPPORTED_SAFE_REASON", "Safe title", "Safe description")),
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).isEmpty();
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .containsExactly(FraudEngineAggregationWarningCode.EVIDENCE_UNSUPPORTED_REASON_CODE_DROPPED)
                .doesNotContain(FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED);
    }

    @Test
    void keepsEvidenceWithoutOptionalReasonCode() {
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "ml.python.primary",
                List.of(evidence(null, "Safe title", "Safe description")),
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).singleElement().extracting(BoundedFraudEngineEvidenceSummary::reasonCode).isNull();
        assertThat(warnings).isEmpty();
    }

    @Test
    void dropsMalformedEvidenceWithoutThrowingAndKeepsSafeEvidence() {
        List<FraudEngineEvidence> source = new ArrayList<>();
        source.add(null);
        source.add(malformedEvidence(null, null, "Safe title", "Safe description", "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE));
        source.add(malformedEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null, null, "Safe description", "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE));
        source.add(malformedEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null, " ", "Safe description", "ML_MODEL", FraudEngineEvidenceStatus.AVAILABLE));
        source.add(malformedEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null, "Safe title", "Safe description", null, FraudEngineEvidenceStatus.AVAILABLE));
        source.add(malformedEvidence(FraudEngineEvidenceType.MODEL_EXPLANATION, null, "Safe title", "Safe description", "ML_MODEL", null));
        source.add(evidence("MODEL_HIGH_RISK", "Safe title", null));
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "ml.python.primary",
                source,
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.title()).isEqualTo("Safe title");
            assertThat(summary.description()).isEqualTo("Bounded engine signal.");
        });
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .containsOnly(FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED);
    }

    @Test
    void dropsUnsafeReasonCodeUsingUnsafeWarningWithoutRawLeakage() {
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "ml.python.primary",
                List.of(malformedEvidence(
                        FraudEngineEvidenceType.MODEL_EXPLANATION,
                        "token.raw.accountId.endpoint",
                        "Safe title",
                        "Safe description",
                        "ML_MODEL",
                        FraudEngineEvidenceStatus.AVAILABLE
                )),
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).isEmpty();
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .containsExactly(FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED);
        assertThat(result + warnings.toString()).doesNotContain("token.raw.accountId.endpoint");
    }

    @Test
    void dropsUnsafeTitlesAndIdentifierVariantsWithoutRawLeakage() {
        List<String> forbidden = forbiddenVariants();
        List<FraudEngineEvidence> source = forbidden.stream()
                .map(title -> malformedEvidence(
                        FraudEngineEvidenceType.MODEL_EXPLANATION,
                        null,
                        title,
                        "Safe description",
                        "ML_MODEL",
                        FraudEngineEvidenceStatus.AVAILABLE
                ))
                .toList();
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineEvidenceSummary> result = sanitizer.sanitize(
                "ml.python.primary",
                source,
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).isEmpty();
        forbidden.forEach(raw -> assertThat(result + warnings.toString()).doesNotContain(raw));
    }

    private FraudEngineEvidence evidence(String reasonCode, String title, String description) {
        return new FraudEngineEvidence(
                FraudEngineEvidenceType.MODEL_EXPLANATION,
                reasonCode,
                title,
                description,
                "ML_MODEL",
                FraudEngineEvidenceStatus.AVAILABLE
        );
    }

    private FraudEngineEvidence malformedEvidence(
            FraudEngineEvidenceType evidenceType,
            String reasonCode,
            String title,
            String description,
            String source,
            FraudEngineEvidenceStatus status
    ) {
        FraudEngineEvidence evidence = mock(FraudEngineEvidence.class);
        when(evidence.evidenceType()).thenReturn(evidenceType);
        when(evidence.reasonCode()).thenReturn(reasonCode);
        when(evidence.title()).thenReturn(title);
        when(evidence.description()).thenReturn(description);
        when(evidence.source()).thenReturn(source);
        when(evidence.status()).thenReturn(status);
        return evidence;
    }

    private List<String> forbiddenVariants() {
        return List.of(
                "transaction_id",
                "txn_id",
                "customer_id",
                "cust_id",
                "account_id",
                "acct_id",
                "card_id",
                "merchant_id",
                "merchant-id",
                "accessToken",
                "bearerToken",
                "stack_trace",
                "exceptionMessage",
                "raw_feature_vector",
                "model_endpoint",
                "apiKey",
                "authorizationBearer"
        );
    }
}
