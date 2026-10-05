package com.frauddetection.alert.consumer;

import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.common.events.contract.TransactionScoredEvent;

import static org.assertj.core.api.Assertions.assertThat;

final class AlertServiceBaselineProjectionAssertions {

    private AlertServiceBaselineProjectionAssertions() {
    }

    static void assertUnaffectedByEngineIntelligence(
            ScoredTransactionDocumentMapper mapper,
            TransactionScoredEvent event
    ) {
        TransactionScoredEvent baselineEvent =
                AlertServiceTransactionScoredEventFixtureLoader.withoutEngineIntelligence();

        ScoredTransactionDocument actual = mapper.toDocument(event);
        ScoredTransactionDocument baseline = mapper.toDocument(baselineEvent);

        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields("sourceEventFingerprint")
                .isEqualTo(baseline);

        assertThat(actual.getSourceEventFingerprint())
                .isEqualTo(ScoringOccurrenceFingerprint.from(event));

        assertThat(baseline.getSourceEventFingerprint())
                .isEqualTo(ScoringOccurrenceFingerprint.from(baselineEvent));

        assertThat(actual.getSourceEventFingerprint())
                .isNotBlank();

        assertThat(baseline.getSourceEventFingerprint())
                .isNotBlank();
    }
}