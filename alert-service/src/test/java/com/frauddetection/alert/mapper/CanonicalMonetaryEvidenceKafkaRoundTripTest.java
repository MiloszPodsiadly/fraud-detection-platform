package com.frauddetection.alert.mapper;

import com.frauddetection.alert.fraudcase.FraudCaseTransactionAssembler;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanonicalMonetaryEvidenceKafkaRoundTripTest {

    private static final String TOPIC = "transactions.scored";

    @Test
    void preservesExactCanonicalDecimalThroughKafkaPersistenceAndFraudCaseAssembly() {
        BigDecimal exactAmount = new BigDecimal("999999999999.99");
        TransactionScoredEvent original = TransactionFixtures.scoredTransaction()
                .withTransactionId("transaction-monetary-round-trip")
                .withFeatureSnapshot(canonicalSnapshot(exactAmount))
                .build();

        byte[] wirePayload = new JacksonKafkaSerializer<TransactionScoredEvent>().serialize(TOPIC, original);
        TransactionScoredEvent roundTripped = new JacksonKafkaDeserializer<>(TransactionScoredEvent.class)
                .deserialize(TOPIC, wirePayload);

        assertThat(roundTripped.featureSnapshot().get(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN))
                .isInstanceOf(BigDecimal.class)
                .isEqualTo(exactAmount);
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(roundTripped.featureSnapshot())).isTrue();
        assertThat(ScoringOccurrenceFingerprint.from(roundTripped))
                .isEqualTo(ScoringOccurrenceFingerprint.from(original));

        ScoredTransactionDocument persisted = new ScoredTransactionDocumentMapper().toDocument(roundTripped);
        assertThat(persisted.getCurrentTransactionAmountPln()).isEqualTo(exactAmount);

        ScoredTransactionRepository repository = mock(ScoredTransactionRepository.class);
        when(repository.findAllById(List.of(roundTripped.transactionId()))).thenReturn(List.of(persisted));
        FraudCaseTransactionAssembler.Assembly assembly = new FraudCaseTransactionAssembler(repository)
                .assemble(List.of(roundTripped.transactionId()), roundTripped.customerId(), roundTripped);

        assertThat(assembly.transactions()).singleElement()
                .satisfies(transaction -> assertThat(transaction.getAmountPln()).isEqualTo(exactAmount));
    }

    @Test
    void doesNotPromoteImpreciseRuntimeFloatingPointInputToTrustedEvidence() {
        TransactionScoredEvent event = TransactionFixtures.scoredTransaction()
                .withFeatureSnapshot(Map.of(
                        FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN,
                        0.1d
                ))
                .build();

        assertThat(event.featureSnapshot().get(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN))
                .isInstanceOf(Double.class);
        assertThat(new ScoredTransactionDocumentMapper().toDocument(event).getCurrentTransactionAmountPln()).isNull();
    }

    private Map<String, Object> canonicalSnapshot(BigDecimal currentAmountPln) {
        return Map.of(
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS,
                List.of("transaction-prior", "transaction-monetary-round-trip"),
                FraudFeatureContract.RECENT_TRANSACTION_COUNT,
                2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW,
                FraudFeatureValueBoundsContract.CANONICAL_RECENT_TRANSACTION_WINDOW_TEXT,
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN,
                FraudFeatureValueBoundsContract.MAX_RECENT_AMOUNT_SUM_PLN,
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW,
                FraudFeatureValueBoundsContract.CANONICAL_RECENT_TRANSACTION_WINDOW_TEXT,
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN,
                currentAmountPln
        );
    }
}
