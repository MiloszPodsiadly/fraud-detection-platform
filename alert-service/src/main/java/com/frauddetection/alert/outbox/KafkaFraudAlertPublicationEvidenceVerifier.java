package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KafkaFraudAlertPublicationEvidenceVerifier implements FraudAlertPublicationEvidenceVerifier {

    static final String VERIFIER_ID = "alert-service:kafka-fraud-alert-offset-verifier";
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "topic=([A-Za-z0-9._-]{1,249}),partition=(0|[1-9][0-9]{0,9}),offset=(0|[1-9][0-9]{0,18})"
    );

    private final ConsumerFactory<String, FraudAlertEvent> consumerFactory;
    private final String fraudAlertsTopic;
    private final Clock clock;

    @Autowired
    public KafkaFraudAlertPublicationEvidenceVerifier(
            @Qualifier("fraudAlertEvidenceConsumerFactory")
            ConsumerFactory<String, FraudAlertEvent> consumerFactory,
            KafkaTopicProperties topicProperties
    ) {
        this(consumerFactory, topicProperties.fraudAlerts(), Clock.systemUTC());
    }

    KafkaFraudAlertPublicationEvidenceVerifier(
            ConsumerFactory<String, FraudAlertEvent> consumerFactory,
            String fraudAlertsTopic,
            Clock clock
    ) {
        this.consumerFactory = Objects.requireNonNull(consumerFactory, "consumerFactory is required");
        this.fraudAlertsTopic = Objects.requireNonNull(fraudAlertsTopic, "fraudAlertsTopic is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public ResolutionEvidenceReference verifyPublished(
            FraudAlertOutboxRecord authoritativeRecord,
            ResolutionEvidenceReference claimedEvidence
    ) {
        FraudAlertOutboxRecord source = Objects.requireNonNull(
                authoritativeRecord,
                "authoritativeRecord is required"
        );
        BrokerOffsetReference reference = parse(claimedEvidence == null ? null : claimedEvidence.reference());
        if (!fraudAlertsTopic.equals(reference.topic())) {
            throw invalid("broker evidence topic does not match the configured fraud alerts topic");
        }
        if (source.getPayload() == null) {
            throw invalid("authoritative fraud alert payload is unavailable for broker verification");
        }

        TopicPartition topicPartition = new TopicPartition(reference.topic(), reference.partition());
        try (Consumer<String, FraudAlertEvent> consumer = consumerFactory.createConsumer()) {
            boolean knownPartition = consumer.partitionsFor(reference.topic(), LOOKUP_TIMEOUT).stream()
                    .anyMatch(partition -> partition.partition() == reference.partition());
            if (!knownPartition) {
                throw invalid("broker evidence partition does not exist");
            }
            consumer.assign(List.of(topicPartition));
            Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(List.of(topicPartition), LOOKUP_TIMEOUT);
            Map<TopicPartition, Long> ends = consumer.endOffsets(List.of(topicPartition), LOOKUP_TIMEOUT);
            Long beginning = beginnings.get(topicPartition);
            Long end = ends.get(topicPartition);
            if (beginning == null || end == null || reference.offset() < beginning || reference.offset() >= end) {
                throw invalid("broker offset is unavailable or no longer retained");
            }
            consumer.seek(topicPartition, reference.offset());
            ConsumerRecords<String, FraudAlertEvent> records = consumer.poll(LOOKUP_TIMEOUT);
            ConsumerRecord<String, FraudAlertEvent> brokerRecord = records.records(topicPartition).stream()
                    .filter(candidate -> candidate.offset() == reference.offset())
                    .findFirst()
                    .orElseThrow(() -> invalid("broker offset could not be read"));
            if (!matchesAuthoritativeEvent(source, brokerRecord)) {
                throw invalid("broker record does not match the authoritative fraud alert event");
            }
            return new ResolutionEvidenceReference(
                    ResolutionEvidenceType.BROKER_OFFSET,
                    reference.canonical(),
                    clock.instant(),
                    VERIFIER_ID
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "broker evidence verification is unavailable",
                    exception
            );
        }
    }

    private boolean matchesAuthoritativeEvent(
            FraudAlertOutboxRecord source,
            ConsumerRecord<String, FraudAlertEvent> brokerRecord
    ) {
        FraudAlertEvent brokerEvent = brokerRecord.value();
        return brokerEvent != null
                && Objects.equals(source.getAlertId(), brokerRecord.key())
                && Objects.equals(source.getEventId(), brokerEvent.eventId())
                && Objects.equals(source.getAlertId(), brokerEvent.alertId())
                && Objects.equals(source.getTransactionId(), brokerEvent.transactionId())
                && Objects.equals(source.getPayload(), brokerEvent);
    }

    private BrokerOffsetReference parse(String value) {
        Matcher matcher = REFERENCE_PATTERN.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            throw invalid("broker offset evidence must use topic=<topic>,partition=<partition>,offset=<offset>");
        }
        try {
            return new BrokerOffsetReference(
                    matcher.group(1),
                    Integer.parseInt(matcher.group(2)),
                    Long.parseLong(matcher.group(3))
            );
        } catch (NumberFormatException exception) {
            throw invalid("broker offset evidence contains an invalid partition or offset");
        }
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record BrokerOffsetReference(String topic, int partition, long offset) {
        private String canonical() {
            return "topic=" + topic + ",partition=" + partition + ",offset=" + offset;
        }
    }
}
