package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.common.events.observability.TraceContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaFraudDecisionEventPublisherTest {

    @Test
    void shouldPropagateTraceIdFromMdcIntoKafkaHeaders() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, com.frauddetection.common.events.contract.FraudDecisionEvent> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        KafkaFraudDecisionEventPublisher publisher = new KafkaFraudDecisionEventPublisher(
                kafkaTemplate,
                new KafkaTopicProperties("transactions.scored", "fraud.alerts", "fraud.decisions", "transactions.dead-letter")
        );

        com.frauddetection.common.events.contract.FraudDecisionEvent event =
                new com.frauddetection.common.events.contract.FraudDecisionEvent(
                        "evt-1",
                        "decision-1",
                        "alert-1",
                        "txn-1",
                        "cust-1",
                        "corr-1",
                        "analyst-1",
                        com.frauddetection.common.events.enums.AnalystDecision.CONFIRMED_FRAUD,
                        com.frauddetection.common.events.enums.AlertStatus.RESOLVED,
                        "Confirmed after manual review",
                        List.of("manual-review"),
                        Map.of(),
                        Instant.parse("2026-04-23T10:00:00Z"),
                        Instant.parse("2026-04-23T10:01:00Z")
                );

        try (TraceContext.Scope ignored = TraceContext.open("corr-1", "trace-456", "txn-1", "alert-1")) {
            publisher.publish(event);
        }

        ArgumentCaptor<ProducerRecord<String, com.frauddetection.common.events.contract.FraudDecisionEvent>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(new String(captor.getValue().headers().lastHeader(TraceContext.KAFKA_TRACE_ID_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("trace-456");
    }
}
