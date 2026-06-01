package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.harness;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.json;
import static com.frauddetection.scoring.service.TransactionFraudScoringServiceEngineIntelligenceTestSupport.summary;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionFraudScoringServiceEngineIntelligenceEmissionTest {

    @Test
    void defaultConfigPublishesEventWithoutEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"");
    }

    @Test
    void explicitFalsePublishesEventWithoutEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"");
    }

    @Test
    void explicitTruePublishesEventWithBoundedEngineIntelligence() throws Exception {
        TransactionScoredEvent event = harness(Optional.of(summary())).scoreAndCapture();
        assertThat(event.engineIntelligence()).isEqualTo(summary());
        assertThat(json(event)).contains("\"engineIntelligence\"");
    }

    @Test
    void enabledEmissionFailurePublishesBaseEvent() throws Exception {
        TransactionScoredEvent event = harness(Optional.empty()).scoreAndCapture();
        assertThat(event.engineIntelligence()).isNull();
        assertThat(json(event)).doesNotContain("\"engineIntelligence\"", "raw-secret");
    }

    @Test
    void enabledEmissionDoesNotChangeBaseScoringFields() {
        TransactionScoredEvent disabled = harness(Optional.empty()).scoreAndCapture();
        TransactionScoredEvent enabled = harness(Optional.of(summary())).scoreAndCapture();

        assertThat(enabled)
                .usingRecursiveComparison()
                .ignoringFields("eventId", "createdAt", "engineIntelligence")
                .isEqualTo(disabled);
    }
}
