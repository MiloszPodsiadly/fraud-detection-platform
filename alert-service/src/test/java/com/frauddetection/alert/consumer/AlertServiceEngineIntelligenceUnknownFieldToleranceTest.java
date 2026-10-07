package com.frauddetection.alert.consumer;

import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceEngineIntelligenceUnknownFieldToleranceTest {

    private final ScoredTransactionDocumentMapper mapper = new ScoredTransactionDocumentMapper();

    @Test
    void consumerToleratesUnknownNestedEngineIntelligenceField() {
        assertThat(AlertServiceTransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFields().engineIntelligence())
                .isNotNull();
    }

    @Test
    void consumerToleratesUnknownTopLevelFieldIfContractRequiresIt() {
        assertThat(AlertServiceTransactionScoredEventFixtureLoader.unknownTopLevelField().transactionId())
                .isEqualTo("txn-fdp93-001");
    }

    @Test
    void consumerKeepsExistingProjectionUnchangedWhenUnknownFieldsPresent() {
        TransactionScoredEvent unknownNested = AlertServiceTransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFields();

        AlertServiceBaselineProjectionAssertions.assertBaselineDecisionFieldsUnaffected(
                mapper,
                unknownNested,
                MlPredictionEvidenceOmissionReason.ML_ENGINE_UNAVAILABLE
        );
    }
}
