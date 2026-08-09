package com.frauddetection.scoring.integration;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import com.frauddetection.scoring.service.RulesFeatureInputValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class FraudScoringInvalidRulesInputKafkaIntegrationTest extends AbstractIntegrationTest {
    private static final String TOPIC_SUFFIX = UUID.randomUUID().toString();
    private static final String ENRICHED_TOPIC = "transactions.enriched.invalid-rules." + TOPIC_SUFFIX;
    private static final String SCORED_TOPIC = "transactions.scored.invalid-rules." + TOPIC_SUFFIX;
    private static final String DLT_TOPIC = "transactions.dead-letter.invalid-rules." + TOPIC_SUFFIX;

    @Autowired
    private KafkaTemplate<String, TransactionEnrichedEvent> enrichedEventKafkaTemplate;

    @Autowired
    private RuleBasedFraudScoringEngine ruleBasedFraudScoringEngine;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private EngineIntelligenceEmissionService engineIntelligenceEmissionService;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("app.kafka.topics.transaction-enriched", () -> ENRICHED_TOPIC);
        registry.add("app.kafka.topics.transaction-scored", () -> SCORED_TOPIC);
        registry.add("app.kafka.topics.transactions-dead-letter", () -> DLT_TOPIC);
        registry.add("app.kafka.consumer.retry-attempts", () -> "2");
        registry.add("app.kafka.consumer.retry-backoff-millis", () -> "10");
    }

    @Test
    void invalidRulesInputIsRetriedAndDeadLetteredWithoutPublishingScoredOrEngineIntelligence() {
        TransactionEnrichedEvent invalidEvent = invalidRulesInputEvent();

        assertThatThrownBy(() -> ruleBasedFraudScoringEngine.score(FraudScoringRequest.from(invalidEvent)))
                .isInstanceOf(RulesFeatureInputValidationException.class)
                .hasMessage("RULES_FEATURE_INPUT_INVALID")
                .hasMessageNotContaining("P1D")
                .hasMessageNotContaining(invalidEvent.transactionId());

        enrichedEventKafkaTemplate.send(ENRICHED_TOPIC, invalidEvent.transactionId(), invalidEvent);
        enrichedEventKafkaTemplate.flush();

        ConsumerRecord<String, TransactionEnrichedEvent> deadLetter = pollDeadLetterRecord(invalidEvent.transactionId());
        assertThat(deadLetter.value().transactionId()).isEqualTo(invalidEvent.transactionId());
        assertThat(deadLetter.value().featureSnapshot()).containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P1D");
        assertThat(pollScoredRecord(invalidEvent.transactionId())).isNull();
        verifyNoInteractions(engineIntelligenceEmissionService);
        assertRetryPolicyWasApplied();
        assertBoundedDeadLetterHeaders(deadLetter);
    }

    private void assertRetryPolicyWasApplied() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            double failures = meterRegistry.counter(
                    "fraud.scoring.requests",
                    "mode", "rule_based",
                    "outcome", "failure",
                    "fallback_used", "false",
                    "risk_level", "unknown"
            ).count();
            if (failures >= 2.0d) {
                assertThat(failures).isEqualTo(2.0d);
                return;
            }
        }
        throw new AssertionError("Configured Kafka retry policy did not produce the expected failed attempts.");
    }

    private void assertBoundedDeadLetterHeaders(ConsumerRecord<String, TransactionEnrichedEvent> deadLetter) {
        String headers = headersText(deadLetter);

        assertThat(headers)
                .contains("RulesFeatureInputValidationException")
                .contains("RULES_FEATURE_INPUT_INVALID")
                .doesNotContain("P1D")
                .doesNotContain("recentTransactionCountWindow")
                .doesNotContain("featureSnapshot")
                .doesNotContain("rules.validation")
                .doesNotContain("fraud.rules.validation");
    }

    private String headersText(ConsumerRecord<String, TransactionEnrichedEvent> record) {
        StringBuilder builder = new StringBuilder();
        for (Header header : record.headers()) {
            builder.append(header.key()).append('=');
            if (header.value() != null) {
                builder.append(new String(header.value(), StandardCharsets.UTF_8));
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private ConsumerRecord<String, TransactionEnrichedEvent> pollDeadLetterRecord(String transactionId) {
        try (KafkaConsumer<String, TransactionEnrichedEvent> consumer = consumer(
                "fraud-scoring-invalid-dlt-" + UUID.randomUUID(),
                new JacksonKafkaDeserializer<>(TransactionEnrichedEvent.class)
        )) {
            consumer.subscribe(List.of(DLT_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, TransactionEnrichedEvent> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, TransactionEnrichedEvent> record : records) {
                    if (transactionId.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Invalid Rules input was not published to the dead-letter topic.");
    }

    private ConsumerRecord<String, TransactionScoredEvent> pollScoredRecord(String transactionId) {
        try (KafkaConsumer<String, TransactionScoredEvent> consumer = consumer(
                "fraud-scoring-invalid-scored-" + UUID.randomUUID(),
                new JacksonKafkaDeserializer<>(TransactionScoredEvent.class)
        )) {
            consumer.subscribe(List.of(SCORED_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, TransactionScoredEvent> records = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, TransactionScoredEvent> record : records) {
                    if (transactionId.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    private <T> KafkaConsumer<String, T> consumer(String groupId, JacksonKafkaDeserializer<T> deserializer) {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        return new KafkaConsumer<>(properties, new StringDeserializer(), deserializer);
    }

    private TransactionEnrichedEvent invalidRulesInputEvent() {
        TransactionEnrichedEvent base = TransactionFixtures.enrichedTransaction().build();
        String transactionId = "txn-invalid-rules-" + TOPIC_SUFFIX;
        return new TransactionEnrichedEvent(
                "evt-invalid-rules-" + TOPIC_SUFFIX,
                transactionId,
                "corr-invalid-rules-" + TOPIC_SUFFIX,
                base.customerId(),
                base.accountId(),
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:00Z"),
                new Money(new BigDecimal("100.00"), "PLN"),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                null,
                null,
                null,
                null,
                null,
                base.merchantFrequency7d(),
                false,
                false,
                false,
                List.of(),
                Map.of(
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                        FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P1D"
                )
        );
    }
}
