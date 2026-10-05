package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.mongodb.client.result.UpdateResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class FraudAlertOutboxKafkaReliabilityIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String FRAUD_ALERT_TOPIC = "fraud.alerts.outbox-reliability." + SUFFIX;

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private FraudAlertOutboxWriter writer;
    @Autowired private AlertServiceMetrics metrics;
    @Autowired private FraudAlertOutboxBacklogMonitor backlogMonitor;
    @Autowired private TransactionalOutboxRuntimeReadiness runtimeReadiness;
    @Autowired private FraudAlertOutboxRecoveryService recoveryService;
    @Autowired
    @Qualifier("kafkaFraudAlertEventPublisher")
    private FraudAlertEventPublisher kafkaPublisher;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("spring.kafka.consumer.group-id", () -> "outbox-reliability-baseline." + SUFFIX);
        registry.add("spring.mongodb.uri", () -> FraudPlatformContainers.mongodb().getReplicaSetUrl("outbox_reliability"));
        registry.add("spring.data.redis.host", () -> FraudPlatformContainers.redis().getHost());
        registry.add("spring.data.redis.port", () -> FraudPlatformContainers.redis().getMappedPort(6379));
        registry.add("app.kafka.topics.transaction-scored", () -> "transactions.scored.outbox-reliability." + SUFFIX);
        registry.add("app.kafka.topics.fraud-alerts", () -> FRAUD_ALERT_TOPIC);
        registry.add("app.kafka.topics.fraud-decisions", () -> "fraud.decisions.outbox-reliability." + SUFFIX);
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dead-letter.outbox-reliability." + SUFFIX);
        registry.add("app.kafka.consumer.engine-intelligence-group-id", () -> "engine-intelligence.outbox-reliability." + SUFFIX);
        registry.add("app.kafka.consumer.ml-prediction-evidence-group-id", () -> "ml-evidence.outbox-reliability." + SUFFIX);
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.outbox.recovery.enabled", () -> true);
    }

    @Test
    void ambiguousBrokerAndConfirmationFailuresCannotCauseUnsafeRepublication() {
        assertThat(runtimeReadiness.isReady()).isTrue();
        FraudAlertEvent acknowledgementTimeout = event(
                "outbox-ack-timeout-event",
                "outbox-ack-timeout-alert"
        );
        writer.publish(acknowledgementTimeout);
        FraudAlertEventPublisher lostAcknowledgement = event -> {
            kafkaPublisher.publish(event);
            throw new IllegalStateException("INJECTED_KAFKA_ACKNOWLEDGEMENT_TIMEOUT");
        };

        assertThat(publisher(mongoTemplate, lostAcknowledgement).publishPending(1)).isZero();
        assertConfirmationUnknown(acknowledgementTimeout.eventId());
        assertThat(countBrokerRecords(acknowledgementTimeout.alertId())).isEqualTo(1L);

        FraudAlertEvent confirmationWriteFailure = event(
                "outbox-confirmation-write-event",
                "outbox-confirmation-write-alert"
        );
        writer.publish(confirmationWriteFailure);

        assertThat(publisher(confirmationWriteFailureTemplate(), kafkaPublisher).publishPending(1)).isZero();
        assertConfirmationUnknown(confirmationWriteFailure.eventId());
        assertThat(countBrokerRecords(confirmationWriteFailure.alertId())).isEqualTo(1L);

        assertThatThrownBy(() -> recoveryService.resolveConfirmation(
                acknowledgementTimeout.eventId(),
                unverifiedNonDeliveryClaim(),
                "reliability-operator",
                "unverified-non-delivery-claim"
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        assertThat(publisher(mongoTemplate, kafkaPublisher).publishPending(10)).isZero();
        assertConfirmationUnknown(acknowledgementTimeout.eventId());
        assertConfirmationUnknown(confirmationWriteFailure.eventId());
        assertThat(countBrokerRecords(acknowledgementTimeout.alertId())).isEqualTo(1L);
        assertThat(countBrokerRecords(confirmationWriteFailure.alertId())).isEqualTo(1L);
    }

    private FraudAlertOutboxPublisher publisher(
            MongoTemplate template,
            FraudAlertEventPublisher brokerPublisher
    ) {
        return new FraudAlertOutboxPublisher(
                template,
                brokerPublisher,
                metrics,
                backlogMonitor,
                new OutboxOperationalControls(true, true),
                runtimeReadiness,
                Duration.ofMinutes(1),
                5
        );
    }

    private MongoTemplate confirmationWriteFailureTemplate() {
        return new MongoTemplate(mongoTemplate.getMongoDatabaseFactory()) {
            @Override
            public UpdateResult updateFirst(Query query, UpdateDefinition update, Class<?> entityClass) {
                Object setValue = update.getUpdateObject().get("$set");
                Object status = setValue instanceof Document set ? set.get("status") : null;
                if (FraudAlertOutboxRecord.class.equals(entityClass)
                        && FraudAlertOutboxStatus.PUBLISHED.name().equals(String.valueOf(status))) {
                    throw new IllegalStateException("INJECTED_MONGO_CONFIRMATION_WRITE_FAILURE");
                }
                return super.updateFirst(query, update, entityClass);
            }
        };
    }

    private FraudAlertOutboxConfirmationResolutionRequest unverifiedNonDeliveryClaim() {
        return new FraudAlertOutboxConfirmationResolutionRequest(
                FraudAlertOutboxConfirmationResolution.CONFIRMED_NOT_DELIVERED,
                "operator could not find a matching record",
                new ResolutionEvidenceReference(
                        ResolutionEvidenceType.BROKER_NON_DELIVERY,
                        "operator-search=not-found",
                        Instant.now(),
                        "operator-claim"
                )
        );
    }

    private void assertConfirmationUnknown(String eventId) {
        assertThat(mongoTemplate.findById(eventId, FraudAlertOutboxRecord.class))
                .isNotNull()
                .satisfies(record -> {
                    assertThat(record.getStatus()).isEqualTo(FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
                    assertThat(record.getAttempts()).isEqualTo(1);
                });
    }

    private long countBrokerRecords(String alertId) {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-reliability-inspection." + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                properties,
                new StringDeserializer(),
                new ByteArrayDeserializer()
        )) {
            List<TopicPartition> partitions = consumer.partitionsFor(FRAUD_ALERT_TOPIC).stream()
                    .map(partition -> new TopicPartition(FRAUD_ALERT_TOPIC, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            long matches = 0L;
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < endOffsets.get(partition))) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofSeconds(1))) {
                    if (alertId.equals(record.key())) {
                        matches++;
                    }
                }
            }
            return matches;
        }
    }

    private FraudAlertEvent event(String eventId, String alertId) {
        return new FraudAlertEvent(
                eventId,
                alertId,
                "transaction-" + eventId,
                "customer-reliability-proof",
                "correlation-" + eventId,
                Instant.parse("2026-10-05T10:00:00Z"),
                Instant.parse("2026-10-05T10:00:00Z"),
                RiskLevel.HIGH,
                0.91d,
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
