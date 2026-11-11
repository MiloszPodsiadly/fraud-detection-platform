package com.frauddetection.enricher.messaging;

import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.KafkaTopicProperties;
import com.frauddetection.enricher.service.TransactionFeatureEnrichmentUseCase;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TransactionRawEventListenerOriginProofTest {
    @Test void forwardsPrivateKafkaOriginProofSeparatelyFromTheEventBody() {
        var event = TransactionFixtures.rawTransaction().build();
        var useCase = mock(TransactionFeatureEnrichmentUseCase.class);
        var listener = new TransactionRawEventListener(useCase, new KafkaTopicProperties("raw", "enriched", "dlt"));
        String proof = "opaque-internal-proof";

        listener.onMessage(event, null, proof);

        verify(useCase).enrich(event, proof);
    }
}
