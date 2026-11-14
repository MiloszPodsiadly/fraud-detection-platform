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
    void preservesBusinessCorrelationHeadersAndPropagatesScopedTraceId() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, com.frauddetection.common.events.contract.TransactionScoredEvent> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        KafkaTransactionScoredEventPublisher publisher = new KafkaTransactionScoredEventPublisher(
                kafkaTemplate,
                new KafkaTopicProperties("transactions.enriched", "transactions.scored", "transactions.dead-letter")
        );

        var event = TransactionFixtures.scoredTransaction().build();
        try (TraceContext.Scope ignored = TraceContext.open(event.correlationId(), "trace-123", event.transactionId(), null)) {
            publisher.publish(event);
        }

        ArgumentCaptor<ProducerRecord<String, com.frauddetection.common.events.contract.TransactionScoredEvent>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(new String(captor.getValue().headers().lastHeader(TraceContext.KAFKA_CORRELATION_ID_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo(event.correlationId());
        assertThat(new String(captor.getValue().headers().lastHeader(TraceContext.KAFKA_TRANSACTION_ID_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo(event.transactionId());
        assertThat(new String(captor.getValue().headers().lastHeader(TraceContext.KAFKA_TRACE_ID_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("trace-123");
    }
}
