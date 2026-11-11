package com.frauddetection.scoring.messaging;

import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.KafkaTopicProperties;
import com.frauddetection.scoring.service.TransactionFraudScoringUseCase;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TransactionEnrichedEventListenerOriginProofTest {
    @Test void forwardsPrivateKafkaOriginProofSeparatelyFromTheEventBody() {
        var event = TransactionFixtures.enrichedTransaction().build();
        var useCase = mock(TransactionFraudScoringUseCase.class);
        var listener = new TransactionEnrichedEventListener(useCase, new KafkaTopicProperties("enriched", "scored", "dlt"));
        String proof = "opaque-internal-proof";

        listener.onMessage(event, null, proof);

        verify(useCase).score(event, proof);
    }
}
