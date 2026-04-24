package com.frauddetection.scoring.messaging;

import com.frauddetection.common.events.observability.TraceContext;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.KafkaTopicProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaTransactionScoredEventPublisherTest {

    @Test
    void shouldPropagateTraceIdFromMdcIntoKafkaHeaders() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, com.frauddetection.common.events.contract.TransactionScoredEvent> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        KafkaTransactionScoredEventPublisher publisher = new KafkaTransactionScoredEventPublisher(
                kafkaTemplate,
                new KafkaTopicProperties("transactions.enriched", "transactions.scored", "transactions.dead-letter")
        );

        try (TraceContext.Scope ignored = TraceContext.open("corr-1", "trace-123", "txn-1", null)) {
            publisher.publish(TransactionFixtures.scoredTransaction().build());
        }

        ArgumentCaptor<ProducerRecord<String, com.frauddetection.common.events.contract.TransactionScoredEvent>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(new String(captor.getValue().headers().lastHeader(TraceContext.KAFKA_TRACE_ID_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("trace-123");
    }
}
