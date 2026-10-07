package com.frauddetection.alert.consumer;

import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;

import static org.assertj.core.api.Assertions.assertThat;

final class AlertServiceBaselineProjectionAssertions {

    private AlertServiceBaselineProjectionAssertions() {
    }

    static void assertBaselineDecisionFieldsUnaffected(
            ScoredTransactionDocumentMapper mapper,
            TransactionScoredEvent event,
            MlPredictionEvidenceOmissionReason expectedOmissionReason
    ) {
        TransactionScoredEvent baselineEvent =
                AlertServiceTransactionScoredEventFixtureLoader.withoutEngineIntelligence();

        ScoredTransactionDocument actual = mapper.toDocument(event);
        ScoredTransactionDocument baseline = mapper.toDocument(baselineEvent);

        assertThat(actual.getTransactionId()).isEqualTo(baseline.getTransactionId());
        assertThat(actual.getCustomerId()).isEqualTo(baseline.getCustomerId());
        assertThat(actual.getCorrelationId()).isEqualTo(baseline.getCorrelationId());
        assertThat(actual.getTransactionTimestamp()).isEqualTo(baseline.getTransactionTimestamp());
        assertThat(actual.getTransactionAmount()).isEqualTo(baseline.getTransactionAmount());
        assertThat(actual.getMerchantInfo()).isEqualTo(baseline.getMerchantInfo());
        assertThat(actual.getTransactionIdSearch()).isEqualTo(baseline.getTransactionIdSearch());
        assertThat(actual.getCustomerIdSearch()).isEqualTo(baseline.getCustomerIdSearch());
        assertThat(actual.getMerchantIdSearch()).isEqualTo(baseline.getMerchantIdSearch());
        assertThat(actual.getCurrencySearch()).isEqualTo(baseline.getCurrencySearch());
        assertThat(actual.getScoredAt()).isEqualTo(baseline.getScoredAt());
        assertThat(actual.getFraudScore()).isEqualTo(baseline.getFraudScore());
        assertThat(actual.getRiskLevel()).isEqualTo(baseline.getRiskLevel());
        assertThat(actual.getAlertRecommended()).isEqualTo(baseline.getAlertRecommended());
        assertThat(actual.getReasonCodes()).isEqualTo(baseline.getReasonCodes());
        assertThat(actual.getAnalystRecommendation()).isEqualTo(baseline.getAnalystRecommendation());

        assertThat(actual.getMlPredictionEvidenceOmissionReason()).isEqualTo(expectedOmissionReason);
        assertThat(actual.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(actual.getSourceEventCreatedAt()).isEqualTo(event.createdAt().toString());
        assertThat(actual.getSourceEventCreatedAtEpochSecond()).isEqualTo(event.createdAt().getEpochSecond());
        assertThat(actual.getSourceEventCreatedAtNano()).isEqualTo(event.createdAt().getNano());
        assertThat(actual.getSourceEventFingerprint())
                .isNotBlank()
                .isEqualTo(ScoringOccurrenceFingerprint.from(event));
    }
}
