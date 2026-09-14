package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionEnrichedEventFeatureSnapshotSerdeTest {
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final JacksonKafkaSerializer<TransactionEnrichedEvent> serializer = new JacksonKafkaSerializer<>();
    private final JacksonKafkaDeserializer<TransactionEnrichedEvent> deserializer =
            new JacksonKafkaDeserializer<>(TransactionEnrichedEvent.class);
    private final JacksonKafkaSerializer<TransactionScoredEvent> scoredSerializer = new JacksonKafkaSerializer<>();
    private final JacksonKafkaDeserializer<TransactionScoredEvent> scoredDeserializer =
            new JacksonKafkaDeserializer<>(TransactionScoredEvent.class);

    @Test
    void kafkaSerdeReplaysProducerFeatureSnapshotWithRegisteredRuntimeTypes() {
        TransactionEnrichedEvent event = event(Map.ofEntries(
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5),
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"),
                Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00")),
                Map.entry(FraudFeatureContract.RAPID_TRANSFER_COUNT, 5),
                Map.entry(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("20000.00")),
                Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d),
                Map.entry(FraudFeatureContract.COUNTRY_MISMATCH, false),
                Map.entry("futureAdditiveFeature", Map.of("nested", List.of(1, "two")))
        ));

        TransactionEnrichedEvent replayed = deserialize(event);
        Map<String, Object> snapshot = replayed.featureSnapshot();

        assertThat(snapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT))
                .isEqualTo(5)
                .isExactlyInstanceOf(Integer.class);
        assertThat(snapshot.get(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE))
                .isEqualTo(5.0d)
                .isExactlyInstanceOf(Double.class);
        assertThat(snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN))
                .isEqualTo(new BigDecimal("20000.00"))
                .isExactlyInstanceOf(BigDecimal.class);
        assertThat(snapshot.get(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN))
                .isEqualTo(new BigDecimal("20000.00"))
                .isExactlyInstanceOf(BigDecimal.class);
        assertThat(snapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW))
                .isEqualTo("PT1M")
                .isExactlyInstanceOf(String.class);
        assertThat(snapshot).containsKey("futureAdditiveFeature");
    }

    @Test
    void currentProducerWritesFeatureSnapshotWithoutDuplicateTopLevelFraudFacts() throws IOException {
        String serialized = new String(serializer.serialize("transactions.enriched", event(Map.ofEntries(
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5),
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M"),
                Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00")),
                Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d)
        ))), StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) objectMapper.readTree(serialized);

        assertThat(root.has("featureSnapshot")).isTrue();
        assertThat(root.has("recentTransactionCount")).isFalse();
        assertThat(root.has("recentTransactionCountWindow")).isFalse();
        assertThat(root.has("recentAmountSum")).isFalse();
        assertThat(root.has("recentAmountSumWindow")).isFalse();
        assertThat(root.has("transactionVelocityPerMinute")).isFalse();
        assertThat(root.has("merchantFrequency7d")).isFalse();
        assertThat(root.has("deviceNovelty")).isFalse();
        assertThat(root.has("countryMismatch")).isFalse();
        assertThat(root.has("proxyOrVpnDetected")).isFalse();
    }

    @Test
    void oldPayloadWithDuplicateTopLevelFraudFactsDeserializesIntoCurrentConsumerSnapshot() throws IOException {
        String serialized = new String(serializer.serialize("transactions.enriched", event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00")
        ))), StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) objectMapper.readTree(serialized);
        root.put("recentTransactionCount", 5);
        root.put("recentTransactionCountWindow", "PT1M");
        root.set("recentAmountSum", objectMapper.valueToTree(new Money(new BigDecimal("20000.00"), "PLN")));
        root.put("recentAmountSumWindow", "PT1M");
        root.put("transactionVelocityPerMinute", 5.0d);
        root.put("merchantFrequency7d", 1);
        root.put("deviceNovelty", false);
        root.put("countryMismatch", false);
        root.put("proxyOrVpnDetected", false);

        TransactionEnrichedEvent replayed = deserializer.deserialize(
                "transactions.enriched",
                objectMapper.writeValueAsBytes(root)
        );

        assertThat(replayed.featureSnapshot())
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5);
        assertThat((BigDecimal) replayed.featureSnapshot().get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN))
                .isEqualByComparingTo("20000.00");
    }

    @Test
    void currentPayloadCanBeReadByPrompt2SnapshotBasedConsumerDuringCoordinatedCutover() throws IOException {
        byte[] payload = serializer.serialize("transactions.enriched", event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00")
        )));

        Prompt2SnapshotBasedConsumerEvent replayed = objectMapper.readValue(payload, Prompt2SnapshotBasedConsumerEvent.class);

        assertThat(replayed.featureSnapshot())
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5);
        assertThat((BigDecimal) replayed.featureSnapshot().get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN))
                .isEqualByComparingTo("20000.00");
    }

    @Test
    void kafkaSerdeKeepsMalformedPresentCanonicalValuesInvalidInsteadOfCoercingStringsOrFractions() throws IOException {
        String json = malformedFeatureSnapshotJson(event(Map.of(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5,
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d
        )));

        TransactionEnrichedEvent replayed = deserializer.deserialize("transactions.enriched", json.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> snapshot = replayed.featureSnapshot();

        assertThat(snapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT)).isExactlyInstanceOf(BigDecimal.class);
        assertThat(snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN)).isExactlyInstanceOf(String.class);
    }

    @Test
    void scoredEventKafkaSerdeUsesTheSameFeatureSnapshotWireTypesAsEnrichedEvent() {
        Map<String, Object> sourceSnapshot = Map.ofEntries(
                Map.entry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5),
                Map.entry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00")),
                Map.entry(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN, new BigDecimal("20000.00")),
                Map.entry(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 5.0d)
        );

        TransactionScoredEvent replayed = scoredDeserializer.deserialize(
                "transactions.scored",
                scoredSerializer.serialize("transactions.scored", scoredEvent(sourceSnapshot))
        );
        Map<String, Object> snapshot = replayed.featureSnapshot();

        assertThat(snapshot.get(FraudFeatureContract.RECENT_TRANSACTION_COUNT))
                .isEqualTo(5)
                .isExactlyInstanceOf(Integer.class);
        assertThat(snapshot.get(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE))
                .isEqualTo(5.0d)
                .isExactlyInstanceOf(Double.class);
        assertThat(snapshot.get(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN))
                .isEqualTo(new BigDecimal("20000.00"))
                .isExactlyInstanceOf(BigDecimal.class);
        assertThat(snapshot.get(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN))
                .isEqualTo(new BigDecimal("20000.00"))
                .isExactlyInstanceOf(BigDecimal.class);
    }

    private String malformedFeatureSnapshotJson(TransactionEnrichedEvent event) throws IOException {
        String serialized = new String(serializer.serialize("transactions.enriched", event), StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) objectMapper.readTree(serialized);
        ObjectNode featureSnapshot = (ObjectNode) root.path("featureSnapshot");
        featureSnapshot.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, new BigDecimal("5.5"));
        featureSnapshot.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, "20000.00");
        return objectMapper.writeValueAsString(root);
    }

    private TransactionEnrichedEvent deserialize(TransactionEnrichedEvent event) {
        byte[] bytes = serializer.serialize("transactions.enriched", event);
        return deserializer.deserialize("transactions.enriched", bytes);
    }

    private TransactionEnrichedEvent event(Map<String, Object> featureSnapshot) {
        return new TransactionEnrichedEvent(
                "evt-serde",
                "txn-serde",
                "corr-serde",
                "cust-serde",
                "acct-serde",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:00Z"),
                new Money(new BigDecimal("100.00"), "PLN"),
                new MerchantInfo("m-1", "Merchant", "5732", "electronics", "PL", "ECOMMERCE", false, Map.of()),
                new DeviceInfo("d-1", "fp", "127.0.0.1", "agent", "web", "browser", true, false, false, Map.of()),
                new LocationInfo("PL", "MZ", "Warsaw", "00-001", 52.2297d, 21.0122d, "Europe/Warsaw", false),
                new CustomerContext("cust-serde", "acct-serde", "retail", "example.test", 365, true, true, "PL", "PLN", List.of("d-1"), Map.of()),
                featureSnapshot
        );
    }

    private TransactionScoredEvent scoredEvent(Map<String, Object> featureSnapshot) {
        return new TransactionScoredEvent(
                "evt-scored-serde",
                "txn-scored-serde",
                "corr-scored-serde",
                "cust-scored-serde",
                "acct-scored-serde",
                Instant.parse("2026-06-18T10:00:01Z"),
                Instant.parse("2026-06-18T10:00:00Z"),
                new Money(new BigDecimal("100.00"), "PLN"),
                new MerchantInfo("m-1", "Merchant", "5732", "electronics", "PL", "ECOMMERCE", false, Map.of()),
                new DeviceInfo("d-1", "fp", "127.0.0.1", "agent", "web", "browser", true, false, false, Map.of()),
                new LocationInfo("PL", "MZ", "Warsaw", "00-001", 52.2297d, 21.0122d, "Europe/Warsaw", false),
                new CustomerContext("cust-scored-serde", "acct-scored-serde", "retail", "example.test", 365, true, true, "PL", "PLN", List.of("d-1"), Map.of()),
                0.62d,
                RiskLevel.MEDIUM,
                "ML",
                "model",
                "model-v1",
                Instant.parse("2026-06-18T10:00:01Z"),
                List.of("ML_MODEL_SIGNAL"),
                Map.of(),
                featureSnapshot,
                true,
                List.of()
        );
    }

    private record Prompt2SnapshotBasedConsumerEvent(
            String eventId,
            String transactionId,
            String correlationId,
            String customerId,
            String accountId,
            Instant createdAt,
            Instant transactionTimestamp,
            Money transactionAmount,
            MerchantInfo merchantInfo,
            DeviceInfo deviceInfo,
            LocationInfo locationInfo,
            CustomerContext customerContext,
            Integer recentTransactionCount,
            String recentTransactionCountWindow,
            Money recentAmountSum,
            String recentAmountSumWindow,
            Double transactionVelocityPerMinute,
            Integer merchantFrequency7d,
            Boolean deviceNovelty,
            Boolean countryMismatch,
            Boolean proxyOrVpnDetected,
            @JsonDeserialize(using = com.frauddetection.common.events.features.FeatureSnapshotWireValueDeserializer.class)
            Map<String, Object> featureSnapshot
    ) {
    }
}
