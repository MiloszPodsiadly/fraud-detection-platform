package com.frauddetection.enricher.messaging;

import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.events.source.SourceOriginProperties;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.KafkaTopicProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaTransactionEnrichedEventOriginProofTest {
    @Test void enricherSignsTheOutputStageWithoutChangingItsWirePayload() {
        @SuppressWarnings("unchecked") KafkaTemplate<String, com.frauddetection.common.events.contract.TransactionEnrichedEvent> kafka = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.<SendResult<String, com.frauddetection.common.events.contract.TransactionEnrichedEvent>>completedFuture(null))
                .when(kafka).send(any(ProducerRecord.class));
        var proofs = new SourceOriginProofs(new SourceOriginProperties(true, "origin-key-1",
                Map.of("origin-key-1", Base64.getEncoder().encodeToString(new byte[32]))));
        var factory = new StaticListableBeanFactory(); factory.addBean("proofs", proofs);
        var publisher = new KafkaTransactionEnrichedEventPublisher(kafka, new KafkaTopicProperties("raw", "enriched", "dlt"),
                factory.getBeanProvider(SourceOriginProofs.class));
        var event = TransactionFixtures.enrichedTransaction().build();

        publisher.publish(event, SourceOriginProofs.Assertion.PROVEN_LEGACY);

        ArgumentCaptor<ProducerRecord<String, com.frauddetection.common.events.contract.TransactionEnrichedEvent>> record =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(record.capture());
        String token = new String(record.getValue().headers().lastHeader(SourceOriginProofs.KAFKA_HEADER).value(), StandardCharsets.US_ASCII);
        assertThat(proofs.verifyEnriched(event, token).assertion()).isEqualTo(SourceOriginProofs.Assertion.PROVEN_LEGACY);
        assertThat(record.getValue().value()).isEqualTo(event);
    }
}
