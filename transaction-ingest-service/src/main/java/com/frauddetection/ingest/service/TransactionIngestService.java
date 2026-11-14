package com.frauddetection.ingest.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.config.KafkaTopicProperties;
import com.frauddetection.ingest.mapper.TransactionRequestMapper;
import com.frauddetection.ingest.messaging.TransactionRawEventProducer;
import com.frauddetection.ingest.observability.CorrelationIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

@Service
public class TransactionIngestService implements TransactionIngestUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngestService.class);

    private final TransactionRequestMapper transactionRequestMapper;
    private final TransactionRawEventProducer transactionRawEventProducer;
    private final KafkaTopicProperties kafkaTopicProperties;
    private final CorrelationIdProvider correlationIdProvider;
    private final SourceOriginProofs originProofs;

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
        this.originProofs = null;
    }

    @Autowired
    public TransactionIngestService(TransactionRequestMapper transactionRequestMapper,
                                    TransactionRawEventProducer transactionRawEventProducer,
                                    KafkaTopicProperties kafkaTopicProperties, CorrelationIdProvider correlationIdProvider,
                                    ObjectProvider<SourceOriginProofs> originProofs) {
        this.transactionRequestMapper = transactionRequestMapper;
        this.transactionRawEventProducer = transactionRawEventProducer;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.correlationIdProvider = correlationIdProvider;
        this.originProofs = originProofs.getIfAvailable();
    }

    @Override
    public IngestTransactionResponse ingest(IngestTransactionRequest request) {
        String correlationId = correlationIdProvider.currentOrGenerate();

        log.info("Accepted transaction ingest request.");

        TransactionRawEvent event = transactionRequestMapper.toEvent(request, correlationId);
        if (originProofs == null) { transactionRawEventProducer.publish(event); }
        else { transactionRawEventProducer.publish(event, SourceOriginProofs.Assertion.PROVEN_LEGACY); }

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
