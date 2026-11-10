package com.frauddetection.enricher.messaging;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.observability.TraceContext;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.enricher.config.KafkaTopicProperties;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class KafkaTransactionEnrichedEventPublisher implements TransactionEnrichedEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransactionEnrichedEventPublisher.class);

    private final KafkaTemplate<String, TransactionEnrichedEvent> kafkaTemplate;
    private final KafkaTopicProperties kafkaTopicProperties;
    private final SourceOriginProofs originProofs;

    @Autowired
    public KafkaTransactionEnrichedEventPublisher(
            KafkaTemplate<String, TransactionEnrichedEvent> kafkaTemplate,
            KafkaTopicProperties kafkaTopicProperties,
            ObjectProvider<SourceOriginProofs> originProofs
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.originProofs = originProofs.getIfAvailable();
    }

    public KafkaTransactionEnrichedEventPublisher(KafkaTemplate<String, TransactionEnrichedEvent> kafkaTemplate,
                                                  KafkaTopicProperties kafkaTopicProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.originProofs = null;
    }

    public KafkaTransactionEnrichedEventPublisher(KafkaTemplate<String, TransactionEnrichedEvent> kafkaTemplate,
                                                  KafkaTopicProperties kafkaTopicProperties, SourceOriginProofs originProofs) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.originProofs = originProofs;
    }

    @Override
    public void publish(TransactionEnrichedEvent event) {
        publish(event, null);
    }

    @Override
    public void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) {
        String topic = kafkaTopicProperties.transactionEnriched();
        try {
            ProducerRecord<String, TransactionEnrichedEvent> record = new ProducerRecord<>(topic, event.transactionId(), event);
            if (origin != null) {
                if (originProofs == null) { throw new IllegalStateException("SOURCE_ORIGIN_PROOF_UNAVAILABLE"); }
                record.headers().add(new RecordHeader(SourceOriginProofs.KAFKA_HEADER,
                        originProofs.signEnriched(event, origin).getBytes(StandardCharsets.US_ASCII)));
            }
            record.headers().add(new RecordHeader(TraceContext.KAFKA_CORRELATION_ID_HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader(TraceContext.KAFKA_TRANSACTION_ID_HEADER, event.transactionId().getBytes(StandardCharsets.UTF_8)));
            addTraceHeaderIfPresent(record, TraceContext.currentTraceId());
            kafkaTemplate.send(record).get();
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .addKeyValue("topic", topic)
                    .log("Published enriched transaction event.");
        } catch (Exception exception) {
            throw new FeatureEnrichmentException("Failed to publish enriched transaction event.", exception);
        }
    }

    private void addTraceHeaderIfPresent(ProducerRecord<String, TransactionEnrichedEvent> record, String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        record.headers().add(new RecordHeader(TraceContext.KAFKA_TRACE_ID_HEADER, traceId.getBytes(StandardCharsets.UTF_8)));
    }
}
