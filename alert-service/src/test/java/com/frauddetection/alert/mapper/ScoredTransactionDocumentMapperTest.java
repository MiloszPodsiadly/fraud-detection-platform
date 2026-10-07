package com.frauddetection.alert.mapper;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoredTransactionDocumentMapperTest {

    private final ScoredTransactionDocumentMapper mapper = new ScoredTransactionDocumentMapper();

    @Test
    void shouldPopulateNormalizedIndexedSearchFields() {
        var document = mapper.toDocument(new TransactionScoredEvent(
                "event-1",
                " TxN-ABC-123 ",
                "correlation-1",
                " Customer-123 ",
                "account-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                new Money(BigDecimal.TEN, " PLN "),
                new MerchantInfo(" Merchant-9 ", "Sensitive merchant", "5411", "GROCERY", "PL", "ECOMMERCE", false, Map.of()),
                null,
                null,
                null,
                0.91,
                RiskLevel.CRITICAL,
                "strategy",
                "model",
                "v1",
                Instant.parse("2026-01-01T00:00:01Z"),
                List.of("DEVICE_NOVELTY"),
                Map.of(),
                Map.of(),
                true,
                List.of(),
                null,
                null,
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED,
                null
        ));

        assertThat(document.getTransactionIdSearch()).isEqualTo("txn-abc-123");
        assertThat(document.getCustomerIdSearch()).isEqualTo("customer-123");
        assertThat(document.getMerchantIdSearch()).isEqualTo("merchant-9");
        assertThat(document.getCurrencySearch()).isEqualTo("pln");
        assertThat(document.getSourceEventId()).isEqualTo("event-1");
        assertThat(document.getSourceEventCreatedAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(document.getSourceEventFingerprint()).matches("[0-9a-f]{64}");
        assertThat(mapper.toDomain(document).scoringOccurrenceOwnership())
                .isEqualTo(ScoringOccurrenceOwnership.authoritative(
                        "event-1",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        document.getSourceEventFingerprint()
                ));
    }

    @Test
    void shouldStoreAnalystRecommendationFromEventWithoutRecomputingIt() {
        var event = new TransactionScoredEvent(
                "event-1",
                "txn-1",
                "correlation-1",
                "customer-1",
                "account-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                new Money(BigDecimal.TEN, "PLN"),
                new MerchantInfo("merchant-9", "Merchant", "5411", "GROCERY", "PL", "ECOMMERCE", false, Map.of()),
                null,
                null,
                null,
                0.91,
                RiskLevel.CRITICAL,
                "strategy",
                "model",
                "v1",
                Instant.parse("2026-01-01T00:00:01Z"),
                List.of("DEVICE_NOVELTY"),
                Map.of(),
                Map.of(),
                true,
                List.of(),
                null,
                null,
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED,
                AnalystRecommendationResult.absent()
        );

        var document = mapper.toDocument(event);
        var domain = mapper.toDomain(document);

        assertThat(document.getAnalystRecommendation()).isSameAs(event.analystRecommendation());
        assertThat(domain.analystRecommendation()).isSameAs(event.analystRecommendation());
    }

    @Test
    void shouldRoundTripAuthoritativeMlPredictionEvidenceOmission() {
        var event = new TransactionScoredEvent(
                "event-omission",
                "txn-omission",
                "correlation-1",
                "customer-1",
                "account-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                new Money(BigDecimal.TEN, "PLN"),
                new MerchantInfo("merchant-9", "Merchant", "5411", "GROCERY", "PL", "ECOMMERCE", false, Map.of()),
                null,
                null,
                null,
                0.91,
                RiskLevel.CRITICAL,
                "strategy",
                "model",
                "v1",
                Instant.parse("2026-01-01T00:00:01Z"),
                List.of("DEVICE_NOVELTY"),
                Map.of(),
                Map.of(),
                true,
                List.of(),
                unavailableMlSummary(),
                null,
                MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE,
                AnalystRecommendationResult.absent()
        );

        var document = mapper.toDocument(event);
        var domain = mapper.toDomain(document);

        assertThat(document.getMlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE);
        assertThat(domain.mlPredictionEvidenceOmissionReason())
                .isEqualTo(MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE);
    }

    private EngineIntelligenceSummary unavailableMlSummary() {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                Instant.parse("2026-01-01T00:00:01Z"),
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.CRITICAL,
                                EngineIntelligenceScoreBucket.VERY_HIGH,
                                List.of("DEVICE_NOVELTY")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.UNAVAILABLE,
                                null,
                                EngineIntelligenceScoreBucket.UNAVAILABLE,
                                List.of("ML_MODEL_UNAVAILABLE")
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                List.of()
        );
    }

    @Test
    void shouldRejectIdentityFreeHistoricalProjection() {
        var historical = new com.frauddetection.alert.persistence.ScoredTransactionDocument();
        historical.setTransactionId("txn-historical");

        assertInvalidOccurrence(historical);
    }

    @Test
    void shouldRejectPartiallyPersistedOccurrenceIdentityInsteadOfTreatingItAsHistorical() {
        var corrupted = authoritativeDocument();
        corrupted.setSourceEventFingerprint(null);

        assertThatThrownBy(() -> mapper.toDomain(corrupted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SCORING_OCCURRENCE_IDENTITY_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sourceEventId",
            "sourceEventCreatedAt",
            "sourceEventCreatedAtEpochSecond",
            "sourceEventCreatedAtNano",
            "sourceEventFingerprint"
    })
    void shouldRejectEveryIndependentlyMissingOccurrenceIdentityField(String missingField) {
        var corrupted = authoritativeDocument();
        switch (missingField) {
            case "sourceEventId" -> corrupted.setSourceEventId(null);
            case "sourceEventCreatedAt" -> corrupted.setSourceEventCreatedAt(null);
            case "sourceEventCreatedAtEpochSecond" -> corrupted.setSourceEventCreatedAtEpochSecond(null);
            case "sourceEventCreatedAtNano" -> corrupted.setSourceEventCreatedAtNano(null);
            case "sourceEventFingerprint" -> corrupted.setSourceEventFingerprint(null);
            default -> throw new IllegalArgumentException("Unexpected field: " + missingField);
        }

        assertInvalidOccurrence(corrupted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-fingerprint", "abc", "A234567890123456789012345678901234567890123456789012345678901234"})
    void shouldRejectNonCanonicalSha256Fingerprints(String fingerprint) {
        var corrupted = authoritativeDocument();
        corrupted.setSourceEventFingerprint(fingerprint);

        assertInvalidOccurrence(corrupted);
    }

    @Test
    void shouldRejectMismatchedTimestampRepresentations() {
        var corrupted = authoritativeDocument();
        corrupted.setSourceEventCreatedAtEpochSecond(corrupted.getSourceEventCreatedAtEpochSecond() + 1);

        assertInvalidOccurrence(corrupted);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1_000_000_000})
    void shouldRejectInvalidNanosecondComponent(int invalidNano) {
        var corrupted = authoritativeDocument();
        corrupted.setSourceEventCreatedAtNano(invalidNano);

        assertInvalidOccurrence(corrupted);
    }

    private void assertInvalidOccurrence(ScoredTransactionDocument document) {
        assertThatThrownBy(() -> mapper.toDomain(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SCORING_OCCURRENCE_IDENTITY_INVALID");
    }

    private ScoredTransactionDocument authoritativeDocument() {
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId("txn-authoritative");
        document.setSourceEventId("event-1");
        document.setSourceEventCreatedAt("2026-01-01T00:00:00.123456789Z");
        document.setSourceEventCreatedAtEpochSecond(1_767_225_600L);
        document.setSourceEventCreatedAtNano(123_456_789);
        document.setSourceEventFingerprint("a".repeat(64));
        return document;
    }
}
