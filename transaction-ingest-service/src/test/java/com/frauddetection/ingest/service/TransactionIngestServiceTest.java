package com.frauddetection.ingest.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.config.KafkaTopicProperties;
import com.frauddetection.ingest.controller.TransactionIngestRequestTestData;
import com.frauddetection.ingest.mapper.TransactionRequestMapper;
import com.frauddetection.ingest.messaging.TransactionRawEventProducer;
import com.frauddetection.ingest.observability.CorrelationIdProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionIngestServiceTest {

    @Mock
    private TransactionRequestMapper transactionRequestMapper;

    @Mock
    private TransactionRawEventProducer transactionRawEventProducer;

    @Mock
    private KafkaTopicProperties kafkaTopicProperties;

    @Mock
    private CorrelationIdProvider correlationIdProvider;

    @InjectMocks
    private TransactionIngestService transactionIngestService;

    @Test
    void shouldPublishEventAndReturnAcceptedResponse() {
        TransactionRawEvent event = new TransactionRawEvent(
                "event-1001",
                "txn-1001",
                "corr-1001",
                "cust-1001",
                "acct-1001",
                "card-1001",
                Instant.parse("2026-04-20T10:15:30Z"),
                Instant.parse("2026-04-20T10:15:28Z"),
                null,
                null,
                null,
                null,
                null,
                "PURCHASE",
                "3DS",
                "PAYMENT_GATEWAY",
                "trace-1001",
                Map.of()
        );

        when(transactionRequestMapper.toEvent(any(), anyString())).thenReturn(event);
        when(kafkaTopicProperties.transactionRaw()).thenReturn("transactions.raw");
        when(correlationIdProvider.currentOrGenerate()).thenReturn("corr-generated-1001");

        IngestTransactionResponse response = transactionIngestService.ingest(TransactionIngestRequestTestData.validRequest());

        ArgumentCaptor<TransactionRawEvent> captor = ArgumentCaptor.forClass(TransactionRawEvent.class);
        verify(transactionRawEventProducer).publish(captor.capture());

        assertThat(captor.getValue().eventId()).isEqualTo("event-1001");
        assertThat(response.transactionId()).isEqualTo("txn-1001");
        assertThat(response.eventId()).isEqualTo("event-1001");
        assertThat(response.topic()).isEqualTo("transactions.raw");
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.correlationId()).isEqualTo("corr-generated-1001");
    }
}
