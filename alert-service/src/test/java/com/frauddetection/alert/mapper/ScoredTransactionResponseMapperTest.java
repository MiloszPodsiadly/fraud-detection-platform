package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.domain.ScoringOccurrenceToken;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoredTransactionResponseMapperTest {

    private final ScoredTransactionResponseMapper mapper = new ScoredTransactionResponseMapper(new AlertResponseMapper());

    @Test
    void existingListResponseFieldsRemainUnchangedAndLightweight() {
        var response = mapper.toResponse(scoredTransaction());

        assertThat(response.transactionId()).isEqualTo("txn-1");
        assertThat(response.customerId()).isEqualTo("customer-1");
        assertThat(response.fraudScore()).isEqualTo(0.91d);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(response.reasonCodes()).containsExactly("HIGH_VELOCITY");
        assertThat(response.scoringOccurrenceToken()).isEqualTo(
                ScoringOccurrenceToken.from(scoredTransaction().scoringOccurrenceOwnership())
        );
    }

    @Test
    void privateScoringOccurrenceOwnershipDoesNotLeakIntoPublicResponses() {
        var transaction = new ScoredTransaction(
                "txn-1",
                "customer-1",
                "correlation-1",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z"),
                null,
                null,
                0.91d,
                RiskLevel.CRITICAL,
                true,
                List.of("HIGH_VELOCITY"),
                null,
                ScoringOccurrenceOwnership.authoritative(
                        "private-source-event",
                        Instant.parse("2026-06-18T10:00:02Z"),
                        "a".repeat(64)
                ),
                com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
        );

        var listResponse = mapper.toResponse(transaction);
        var detailResponse = mapper.toDetailResponse(transaction, EngineIntelligenceResponse.absent());

        assertThat(Arrays.stream(listResponse.getClass().getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("sourceEventId", "sourceEventCreatedAt", "scoringOccurrenceOwnership");
        assertThat(Arrays.stream(detailResponse.getClass().getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("sourceEventId", "sourceEventCreatedAt", "scoringOccurrenceOwnership");
        assertThat(detailResponse.scoringOccurrenceToken())
                .isEqualTo(ScoringOccurrenceToken.from(transaction.scoringOccurrenceOwnership()));
        assertThat(detailResponse.toString()).doesNotContain("private-source-event", "a".repeat(64));
    }

    @Test
    void detailResponseAddsDelegatedEngineIntelligence() {
        EngineIntelligenceResponse engineIntelligence = EngineIntelligenceResponse.absent();

        var response = mapper.toDetailResponse(scoredTransaction(), engineIntelligence);

        assertThat(response.engineIntelligence()).isSameAs(engineIntelligence);
    }

    @Test
    void scoredResponsesExposeOnlyBoundedMerchantDisplayFields() {
        MerchantInfo merchantInfo = new MerchantInfo(
                "merchant-1", "Market", "5411", "Grocery", "PL", "ECOMMERCE", true,
                Map.of("opaqueSentinel", "private-merchant-attribute", "nested", Map.of("sentinel", "nested-private-value"))
        );
        ScoredTransaction transaction = scoredTransactionWithMerchant(merchantInfo);

        var list = mapper.toResponse(transaction);
        var detail = mapper.toDetailResponse(transaction, EngineIntelligenceResponse.absent());

        assertThat(list.merchantInfo()).isNotNull();
        assertThat(list.merchantInfo().merchantId()).isEqualTo("merchant-1");
        assertThat(list.merchantInfo().merchantName()).isEqualTo("Market");
        assertThat(list.merchantInfo().merchantCategoryCode()).isEqualTo("5411");
        assertThat(list.merchantInfo().merchantCategory()).isEqualTo("Grocery");
        assertThat(list.merchantInfo().channel()).isEqualTo("ECOMMERCE");
        assertThat(list.merchantInfo().acquiringCountryCode()).isEqualTo("PL");
        assertThat(list.merchantInfo().toString())
                .doesNotContain("private-merchant-attribute", "nested-private-value", "cardPresent", "attributes");
        assertThat(detail.merchantInfo()).isEqualTo(list.merchantInfo());
    }

    @Test
    void detailResponseUsesStoredAnalystRecommendationWhenPresent() {
        var recommendation = AnalystRecommendationResult.absent();

        var response = mapper.toDetailResponse(scoredTransaction(recommendation), EngineIntelligenceResponse.absent());

        assertThat(response.analystRecommendation()).isSameAs(recommendation);
    }

    @Test
    void detailResponseDefaultsOldProjectionToAbsentAnalystRecommendation() {
        var response = mapper.toDetailResponse(scoredTransaction(null), EngineIntelligenceResponse.absent());

        assertThat(response.analystRecommendation().status().name()).isEqualTo("ABSENT");
        assertThat(response.analystRecommendation().generatedAt()).isNull();
    }

    private ScoredTransaction scoredTransaction() {
        return scoredTransaction(null);
    }

    private ScoredTransaction scoredTransaction(AnalystRecommendationResult analystRecommendation) {
        return new ScoredTransaction(
                "txn-1",
                "customer-1",
                "correlation-1",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z"),
                null,
                null,
                0.91d,
                RiskLevel.CRITICAL,
                true,
                List.of("HIGH_VELOCITY"),
                analystRecommendation,
                ScoringOccurrenceOwnership.authoritative(
                        "event-1",
                        Instant.parse("2026-06-18T10:00:01Z"),
                        "a".repeat(64)
                ),
                com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
        );
    }

    private ScoredTransaction scoredTransactionWithMerchant(MerchantInfo merchantInfo) {
        return new ScoredTransaction(
                "txn-1", "customer-1", "correlation-1",
                Instant.parse("2026-06-18T10:00:00Z"), Instant.parse("2026-06-18T10:00:01Z"),
                null, merchantInfo, 0.91d, RiskLevel.CRITICAL, true, List.of("HIGH_VELOCITY"), null,
                ScoringOccurrenceOwnership.authoritative("event-1", Instant.parse("2026-06-18T10:00:01Z"), "a".repeat(64)),
                com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
        );
    }
}
