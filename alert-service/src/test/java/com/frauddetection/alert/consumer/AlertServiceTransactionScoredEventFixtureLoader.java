package com.frauddetection.alert.consumer;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class AlertServiceTransactionScoredEventFixtureLoader {

    private static final String OLD = "transaction_scored_event_v1_without_engine_intelligence.json";
    private static final String MINIMAL = "transaction_scored_event_v2_minimal_engine_intelligence.json";
    private static final String FULL = "transaction_scored_event_v2_full_bounded_engine_intelligence.json";
    private static final String UNKNOWN_NESTED = "transaction_scored_event_v2_unknown_nested_engine_intelligence_fields.json";
    private static final String UNKNOWN_TOP_LEVEL = "transaction_scored_event_v2_unknown_top_level_field.json";
    private static final Set<String> KNOWN_FIXTURES = Set.of(OLD, MINIMAL, FULL, UNKNOWN_NESTED, UNKNOWN_TOP_LEVEL);

    private AlertServiceTransactionScoredEventFixtureLoader() {
    }

    static TransactionScoredEvent oldWithoutEngineIntelligence() {
        return deserialize(readFixture(OLD));
    }

    static TransactionScoredEvent minimalEngineIntelligence() {
        return deserialize(readFixture(MINIMAL));
    }

    static TransactionScoredEvent fullBoundedEngineIntelligence() {
        return deserialize(readFixture(FULL));
    }

    static TransactionScoredEvent unknownNestedEngineIntelligenceFields() {
        return deserialize(readFixture(UNKNOWN_NESTED));
    }

    static TransactionScoredEvent unknownTopLevelField() {
        return deserialize(readFixture(UNKNOWN_TOP_LEVEL));
    }

    static String fullBoundedEngineIntelligenceJson() {
        return readFixture(FULL);
    }

    private static TransactionScoredEvent deserialize(String json) {
        JsonDeserializer<TransactionScoredEvent> deserializer = new JsonDeserializer<>(TransactionScoredEvent.class, false);
        return deserializer.deserialize("transactions.scored", json.getBytes(StandardCharsets.UTF_8));
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
