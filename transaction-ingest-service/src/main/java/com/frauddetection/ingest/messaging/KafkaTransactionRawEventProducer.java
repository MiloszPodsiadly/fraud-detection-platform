package com.frauddetection.ingest.messaging;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.observability.TraceContext;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.ingest.config.KafkaTopicProperties;
import com.frauddetection.ingest.exception.KafkaPublishException;
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
public class KafkaTransactionRawEventProducer implements TransactionRawEventProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransactionRawEventProducer.class);

    private final KafkaTemplate<String, TransactionRawEvent> kafkaTemplate;
    private final KafkaTopicProperties kafkaTopicProperties;
    private final SourceOriginProofs originProofs;

    @Autowired
    public KafkaTransactionRawEventProducer(
            KafkaTemplate<String, TransactionRawEvent> kafkaTemplate,
            KafkaTopicProperties kafkaTopicProperties,
            ObjectProvider<SourceOriginProofs> originProofs
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.originProofs = originProofs.getIfAvailable();
    }

    public KafkaTransactionRawEventProducer(KafkaTemplate<String, TransactionRawEvent> kafkaTemplate,
                                            KafkaTopicProperties kafkaTopicProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.originProofs = null;
    }

    public KafkaTransactionRawEventProducer(KafkaTemplate<String, TransactionRawEvent> kafkaTemplate,
                                            KafkaTopicProperties kafkaTopicProperties, SourceOriginProofs originProofs) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.originProofs = originProofs;
    }

    @Override
    public void publish(TransactionRawEvent event) {
        publish(event, null);
    }

    @Override
    public void publish(TransactionRawEvent event, SourceOriginProofs.Assertion origin) {
        String topic = kafkaTopicProperties.transactionRaw();
        try {
            ProducerRecord<String, TransactionRawEvent> record = new ProducerRecord<>(topic, event.transactionId(), event);
            if (origin != null) {
                if (originProofs == null) { throw new IllegalStateException("SOURCE_ORIGIN_PROOF_UNAVAILABLE"); }
                record.headers().add(new RecordHeader(SourceOriginProofs.KAFKA_HEADER,
                        originProofs.signRaw(event, origin).getBytes(StandardCharsets.US_ASCII)));
            }
            record.headers().add(new RecordHeader(TraceContext.KAFKA_CORRELATION_ID_HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader(TraceContext.KAFKA_TRACE_ID_HEADER, event.traceId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader(TraceContext.KAFKA_TRANSACTION_ID_HEADER, event.transactionId().getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get();
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .addKeyValue("topic", topic)
                    .log("Published raw transaction event.");
        } catch (Exception exception) {
            throw new KafkaPublishException("Failed to publish transaction event.", exception);
        }
    }
}
