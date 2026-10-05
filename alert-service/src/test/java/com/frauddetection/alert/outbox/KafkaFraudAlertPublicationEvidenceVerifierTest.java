package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaFraudAlertPublicationEvidenceVerifierTest {

    private static final String TOPIC = "fraud.alerts";
    private static final TopicPartition TOPIC_PARTITION = new TopicPartition(TOPIC, 1);
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-05T12:00:00Z");

    private final ConsumerFactory<String, FraudAlertEvent> consumerFactory = mock(ConsumerFactory.class);
    private final Consumer<String, FraudAlertEvent> consumer = mock(Consumer.class);
    private final KafkaFraudAlertPublicationEvidenceVerifier verifier =
            new KafkaFraudAlertPublicationEvidenceVerifier(
                    consumerFactory,
                    TOPIC,
                    Clock.fixed(VERIFIED_AT, ZoneOffset.UTC)
            );

    @BeforeEach
    void setUp() {
        when(consumerFactory.createConsumer()).thenReturn(consumer);
        when(consumer.partitionsFor(any(), any())).thenReturn(List.of(
                new PartitionInfo(TOPIC, 1, null, null, null)
        ));
        when(consumer.beginningOffsets(any(), any())).thenReturn(Map.of(TOPIC_PARTITION, 0L));
        when(consumer.endOffsets(any(), any())).thenReturn(Map.of(TOPIC_PARTITION, 100L));
    }

    @Test
    void verifiesExactRetainedBrokerRecordAndReplacesClientVerificationClaims() {
        FraudAlertEvent event = event("event-1", 0.91d);
        when(consumer.poll(any())).thenReturn(records(new ConsumerRecord<>(TOPIC, 1, 42L, "alert-1", event)));

        ResolutionEvidenceReference verified = verifier.verifyPublished(
                record(event),
                evidence("topic=fraud.alerts,partition=1,offset=42")
        );

        assertThat(verified.reference()).isEqualTo("topic=fraud.alerts,partition=1,offset=42");
        assertThat(verified.verifiedAt()).isEqualTo(VERIFIED_AT);
        assertThat(verified.verifiedBy())
                .isEqualTo(KafkaFraudAlertPublicationEvidenceVerifier.VERIFIER_ID);
    }

    @Test
    void rejectsValidLookingReferenceWhenPartitionDoesNotExist() {
        when(consumer.partitionsFor(any(), any())).thenReturn(List.of(
                new PartitionInfo(TOPIC, 0, null, null, null)
        ));

        assertBadRequest("topic=fraud.alerts,partition=1,offset=42", "partition does not exist");
    }

    @Test
    void rejectsOffsetOutsideRetainedBrokerRange() {
        assertBadRequest("topic=fraud.alerts,partition=1,offset=100", "unavailable or no longer retained");
    }

    @Test
    void rejectsArbitraryTextMasqueradingAsBrokerEvidence() {
        assertBadRequest("broker-admin-query=verified-no-record", "must use topic=<topic>");
    }

    @Test
    void rejectsValidReferenceWhenExactBrokerRecordCannotBeRead() {
        when(consumer.poll(any())).thenReturn(ConsumerRecords.empty());

        assertBadRequest("topic=fraud.alerts,partition=1,offset=42", "could not be read");
    }

    @Test
    void rejectsOffsetFromAnotherTopic() {
        assertBadRequest("topic=other.alerts,partition=1,offset=42", "does not match the configured");
    }

    @Test
    void rejectsCorrectEventIdWhenBrokerPayloadDoesNotMatchAuthoritativePayload() {
        FraudAlertEvent authoritative = event("event-1", 0.91d);
        FraudAlertEvent mismatched = event("event-1", 0.72d);
        when(consumer.poll(any())).thenReturn(records(
                new ConsumerRecord<>(TOPIC, 1, 42L, "alert-1", mismatched)
        ));

        assertThatThrownBy(() -> verifier.verifyPublished(
                record(authoritative),
                evidence("topic=fraud.alerts,partition=1,offset=42")
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not match the authoritative fraud alert event");
    }

    @Test
    void brokerLookupFailureIsUnavailableRatherThanAcceptedEvidence() {
        when(consumer.partitionsFor(any(), any())).thenThrow(new IllegalStateException("broker down"));

        assertThatThrownBy(() -> verifier.verifyPublished(
                record(event("event-1", 0.91d)),
                evidence("topic=fraud.alerts,partition=1,offset=42")
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private void assertBadRequest(String reference, String message) {
        assertThatThrownBy(() -> verifier.verifyPublished(
                record(event("event-1", 0.91d)),
                evidence(reference)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(message);
    }

    private ConsumerRecords<String, FraudAlertEvent> records(ConsumerRecord<String, FraudAlertEvent> record) {
        return new ConsumerRecords<>(Map.of(TOPIC_PARTITION, List.of(record)));
    }

    private ResolutionEvidenceReference evidence(String reference) {
        return new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                reference,
                VERIFIED_AT.minusSeconds(60),
                "untrusted-client-claim"
        );
    }

    private FraudAlertOutboxRecord record(FraudAlertEvent event) {
        FraudAlertOutboxRecord record = new FraudAlertOutboxRecord();
        record.setEventId(event.eventId());
        record.setAlertId(event.alertId());
        record.setTransactionId(event.transactionId());
        record.setPayload(event);
        return record;
    }

    private FraudAlertEvent event(String eventId, double score) {
        return new FraudAlertEvent(
                eventId,
                "alert-1",
                "transaction-1",
                "customer-1",
                "correlation-1",
                Instant.parse("2026-10-03T12:00:00Z"),
                Instant.parse("2026-10-03T11:59:59Z"),
                RiskLevel.HIGH,
                score,
                AlertStatus.OPEN,
                "Review required",
                List.of("MODEL_HIGH_RISK"),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of()
        );
    }
}
