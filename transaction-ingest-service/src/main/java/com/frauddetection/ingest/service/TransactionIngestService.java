package com.frauddetection.ingest.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.config.KafkaTopicProperties;
import com.frauddetection.ingest.mapper.TransactionRequestMapper;
import com.frauddetection.ingest.messaging.TransactionRawEventProducer;
import com.frauddetection.ingest.observability.CorrelationIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class TransactionIngestService implements TransactionIngestUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngestService.class);

    private final TransactionRequestMapper transactionRequestMapper;
    private final TransactionRawEventProducer transactionRawEventProducer;
    private final KafkaTopicProperties kafkaTopicProperties;
    private final CorrelationIdProvider correlationIdProvider;

    public TransactionIngestService(
            TransactionRequestMapper transactionRequestMapper,
            TransactionRawEventProducer transactionRawEventProducer,
            KafkaTopicProperties kafkaTopicProperties,
            CorrelationIdProvider correlationIdProvider
    ) {
        this.transactionRequestMapper = transactionRequestMapper;
        this.transactionRawEventProducer = transactionRawEventProducer;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.correlationIdProvider = correlationIdProvider;
    }

    @Override
    public IngestTransactionResponse ingest(IngestTransactionRequest request) {
        String correlationId = correlationIdProvider.currentOrGenerate();

        log.atInfo()
                .addKeyValue("transactionId", request.transactionId())
                .addKeyValue("customerId", request.customerId())
                .addKeyValue("correlationId", correlationId)
                .log("Accepted transaction ingest request.");

        TransactionRawEvent event = transactionRequestMapper.toEvent(request, correlationId);
        transactionRawEventProducer.publish(event);

        return new IngestTransactionResponse(
                request.transactionId(),
                event.eventId(),
                correlationId,
                kafkaTopicProperties.transactionRaw(),
                Instant.now(),
                "ACCEPTED"
        );
    }
}
