package com.frauddetection.ingest.messaging;

import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.events.source.SourceOriginProperties;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.ingest.config.KafkaTopicProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class KafkaTransactionRawEventOriginProofTest {
    @Test void producerGeneratesAnEventBoundPrivateLegacyProofInACustomHeader() {
        @SuppressWarnings("unchecked") KafkaTemplate<String, com.frauddetection.common.events.contract.TransactionRawEvent> kafka = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.<SendResult<String, com.frauddetection.common.events.contract.TransactionRawEvent>>completedFuture(null))
                .when(kafka).send(any(ProducerRecord.class));
        var proofs = new SourceOriginProofs(new SourceOriginProperties(true, "origin-key-1",
                Map.of("origin-key-1", Base64.getEncoder().encodeToString(new byte[32]))));
        var factory = new StaticListableBeanFactory(); factory.addBean("proofs", proofs);
        var producer = new KafkaTransactionRawEventProducer(kafka, new KafkaTopicProperties("raw"),
                factory.getBeanProvider(SourceOriginProofs.class));
        var event = TransactionFixtures.rawTransaction().build();

        producer.publish(event, SourceOriginProofs.Assertion.PROVEN_LEGACY);

        ArgumentCaptor<ProducerRecord<String, com.frauddetection.common.events.contract.TransactionRawEvent>> record =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(record.capture());
        String token = new String(record.getValue().headers().lastHeader(SourceOriginProofs.KAFKA_HEADER).value(), StandardCharsets.US_ASCII);
        assertThat(proofs.verifyRaw(event, token).assertion()).isEqualTo(SourceOriginProofs.Assertion.PROVEN_LEGACY);
        assertThat(record.getValue().value()).isEqualTo(event);
        assertThat(record.getValue().key()).isEqualTo(event.transactionId());
    }

    @Test void attestedRawRecordsUseAStableHashedCustomerPartitionKey() throws Exception {
        @SuppressWarnings("unchecked") KafkaTemplate<String, com.frauddetection.common.events.contract.TransactionRawEvent> kafka = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.<SendResult<String, com.frauddetection.common.events.contract.TransactionRawEvent>>completedFuture(null))
                .when(kafka).send(any(ProducerRecord.class));
        var proofs = new SourceOriginProofs(new SourceOriginProperties(true, "origin-key-1",
                Map.of("origin-key-1", Base64.getEncoder().encodeToString(new byte[32]))));
        var factory = new StaticListableBeanFactory(); factory.addBean("proofs", proofs);
        var producer = new KafkaTransactionRawEventProducer(kafka, new KafkaTopicProperties("raw"),
                factory.getBeanProvider(SourceOriginProofs.class));
        var first = TransactionFixtures.rawTransaction().build();
        var second = new com.frauddetection.common.events.contract.TransactionRawEvent("second-event", "second-transaction",
                "second-correlation", first.customerId(), first.accountId(), first.paymentInstrumentId(), first.createdAt().plusSeconds(1),
                first.transactionTimestamp().plusSeconds(1), first.transactionAmount(), first.merchantInfo(), first.deviceInfo(),
                first.locationInfo(), first.customerContext(), first.transactionType(), first.authorizationMethod(), first.sourceSystem(),
                first.traceId(), first.attributes());

        producer.publish(first, SourceOriginProofs.Assertion.KNOWN_ATTESTED);
        producer.publish(second, SourceOriginProofs.Assertion.KNOWN_ATTESTED);

        ArgumentCaptor<ProducerRecord<String, com.frauddetection.common.events.contract.TransactionRawEvent>> records =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(records.capture());
        String expectedKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("source-customer-partition/v1\u0000" + first.customerId()).getBytes(StandardCharsets.UTF_8)));
        assertThat(records.getAllValues()).extracting(ProducerRecord::key).containsExactly(expectedKey, expectedKey);
        assertThat(expectedKey).doesNotContain(first.customerId()).doesNotContain(first.transactionId());
    }
}
