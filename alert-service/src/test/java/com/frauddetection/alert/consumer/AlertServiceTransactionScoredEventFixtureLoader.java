package com.frauddetection.alert.consumer;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class AlertServiceTransactionScoredEventFixtureLoader {

    private static final String WITHOUT_ENGINE_INTELLIGENCE =
            "transaction_scored_event_v2_without_engine_intelligence.json";
    private static final String MINIMAL = "transaction_scored_event_v2_minimal_engine_intelligence.json";
    private static final String FULL = "transaction_scored_event_v2_full_bounded_engine_intelligence.json";
    private static final String UNKNOWN_NESTED = "transaction_scored_event_v2_unknown_nested_engine_intelligence_fields.json";
    private static final String UNKNOWN_TOP_LEVEL = "transaction_scored_event_v2_unknown_top_level_field.json";
    private static final Set<String> KNOWN_FIXTURES = Set.of(
            WITHOUT_ENGINE_INTELLIGENCE,
            MINIMAL,
            FULL,
            UNKNOWN_NESTED,
            UNKNOWN_TOP_LEVEL
    );

    private static final JacksonKafkaDeserializer<TransactionScoredEvent> DESERIALIZER =
            new JacksonKafkaDeserializer<>(TransactionScoredEvent.class);

    private AlertServiceTransactionScoredEventFixtureLoader() {
    }

    static TransactionScoredEvent withoutEngineIntelligence() {
        return deserializeJson(readFixture(WITHOUT_ENGINE_INTELLIGENCE));
    }

    static TransactionScoredEvent minimalEngineIntelligence() {
        return deserializeJson(readFixture(MINIMAL));
    }

    static TransactionScoredEvent fullBoundedEngineIntelligence() {
        return deserializeJson(readFixture(FULL));
    }

    static TransactionScoredEvent unknownNestedEngineIntelligenceFields() {
        return deserializeJson(readFixture(UNKNOWN_NESTED));
    }

    static TransactionScoredEvent unknownTopLevelField() {
        return deserializeJson(readFixture(UNKNOWN_TOP_LEVEL));
    }

    static String minimalEngineIntelligenceJson() {
        return readFixture(MINIMAL);
    }

    static String fullBoundedEngineIntelligenceJson() {
        return readFixture(FULL);
    }

    static TransactionScoredEvent deserializeJson(String json) {
        return DESERIALIZER.deserialize("transactions.scored", json.getBytes(StandardCharsets.UTF_8));
    }

    private static String readFixture(String name) {
        if (!KNOWN_FIXTURES.contains(name)) {
            throw new IllegalArgumentException("TRANSACTION_SCORED_EVENT_FIXTURE_UNKNOWN");
        }
        try {
            return Files.readString(fixtureRoot().resolve(name));
        } catch (IOException exception) {
            throw new IllegalStateException("TRANSACTION_SCORED_EVENT_FIXTURE_READ_FAILED", exception);
        }
    }

    private static Path fixtureRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path fixtureRoot = candidate.resolve("common-events/src/test/resources/fixtures/transaction-scored-event");
            if (Files.isDirectory(fixtureRoot)) {
                return fixtureRoot;
            }
        }
        throw new IllegalStateException("TRANSACTION_SCORED_EVENT_FIXTURE_ROOT_MISSING");
    }
}
