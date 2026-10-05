package com.frauddetection.alert.consumer;

import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceIgnoresEngineIntelligenceProjectionTest {

    private static final List<String> FORBIDDEN_PROJECTION_FIELDS = List.of(
            "engineIntelligence",
            "engineResults",
            "diagnosticSignals",
            "agreementStatus",
            "riskMismatchStatus",
            "scoreDeltaBucket",
            "engineIntelligenceWarnings",
            "finalDecision",
            "recommendedAction",
            "platformRiskScore"
    );

    private final ScoredTransactionDocumentMapper mapper =
            new ScoredTransactionDocumentMapper();

    @Test
    void currentEventWithoutEngineIntelligenceProducesBaselineProjectionShape() {
        assertThat(
                mapper.toDocument(
                        AlertServiceTransactionScoredEventFixtureLoader.withoutEngineIntelligence()
                )
        ).isNotNull();
    }

    @Test
    void minimalEngineIntelligenceDoesNotAddProjectionFields() {
        assertProjectionUnchanged(
                AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligence()
        );
    }

    @Test
    void fullBoundedEngineIntelligenceDoesNotAddProjectionFields() {
        assertProjectionUnchanged(
                AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence()
        );
    }

    @Test
    void unknownNestedEngineIntelligenceFieldsDoNotAffectProjection() {
        assertProjectionUnchanged(
                AlertServiceTransactionScoredEventFixtureLoader
                        .unknownNestedEngineIntelligenceFields()
        );
    }

    @Test
    void engineIntelligenceIsNotPersisted() {
        for (Class<?> documentType : List.of(
                ScoredTransactionDocument.class,
                AlertDocument.class,
                FraudCaseDocument.class,
                FraudCaseTransactionDocument.class,
                SuspiciousTransactionDocument.class
        )) {
            assertThat(
                    Arrays.stream(documentType.getDeclaredFields())
                            .map(Field::getName)
            ).noneMatch(FORBIDDEN_PROJECTION_FIELDS::contains);
        }
    }

    @Test
    void occurrenceFingerprintStillMatchesExactConsumedEvent() {
        TransactionScoredEvent event =
                AlertServiceTransactionScoredEventFixtureLoader
                        .fullBoundedEngineIntelligence();

        ScoredTransactionDocument document = mapper.toDocument(event);

        assertThat(document.getSourceEventFingerprint())
                .isEqualTo(ScoringOccurrenceFingerprint.from(event));
    }

    private void assertProjectionUnchanged(TransactionScoredEvent event) {
        AlertServiceBaselineProjectionAssertions.assertUnaffectedByEngineIntelligence(
                mapper,
                event
        );
    }
}