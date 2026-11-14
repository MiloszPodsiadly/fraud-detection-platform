package com.frauddetection.alert.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.observability.TraceContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

class KafkaFraudAlertEventPublisherTest {

    @Test
    void preservesAlertHeadersAndKeepsBusinessIdentifiersOutOfApplicationLogs() throws Exception {
        @SuppressWarnings("unchecked") KafkaTemplate<String, FraudAlertEvent> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new KafkaFraudAlertEventPublisher(kafkaTemplate,
                new KafkaTopicProperties("transactions.scored", "fraud.alerts", "fraud.decisions", "transactions.dead-letter"));
        var event = new FraudAlertEvent("event-canary", "alert-canary", "transaction-canary", "customer-canary",
                "correlation-canary", Instant.parse("2026-11-12T12:00:00Z"), Instant.parse("2026-11-12T12:00:00Z"),
                RiskLevel.HIGH, 0.9, AlertStatus.OPEN, "review", List.of("MODEL_HIGH_RISK"), null, null, null,
                null, null, Map.of(), Map.of());

        Logger logger = (Logger) LoggerFactory.getLogger(KafkaFraudAlertEventPublisher.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try (TraceContext.Scope ignored = TraceContext.open(event.correlationId(), "trace-canary", event.transactionId(), event.alertId())) {
            publisher.publish(event);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }

        ArgumentCaptor<ProducerRecord<String, FraudAlertEvent>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().value()).isEqualTo(event);
        assertThat(header(captor.getValue(), TraceContext.KAFKA_CORRELATION_ID_HEADER)).isEqualTo(event.correlationId());
        assertThat(header(captor.getValue(), TraceContext.KAFKA_TRANSACTION_ID_HEADER)).isEqualTo(event.transactionId());
        assertThat(header(captor.getValue(), TraceContext.KAFKA_ALERT_ID_HEADER)).isEqualTo(event.alertId());
        assertThat(header(captor.getValue(), TraceContext.KAFKA_TRACE_ID_HEADER)).isEqualTo("trace-canary");
        String logs = appender.list.toString();
        assertThat(logs).doesNotContain("event-canary", "alert-canary", "transaction-canary", "customer-canary", "correlation-canary", "trace-canary");
    }

    private String header(ProducerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
