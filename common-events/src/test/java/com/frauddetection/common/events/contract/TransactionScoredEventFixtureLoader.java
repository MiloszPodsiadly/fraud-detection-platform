package com.frauddetection.common.events.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class TransactionScoredEventFixtureLoader {

    private static final String ROOT = "fixtures/transaction-scored-event/";
    private static final String OLD = "transaction_scored_event_v1_without_engine_intelligence.json";
    private static final String MINIMAL = "transaction_scored_event_v2_minimal_engine_intelligence.json";
    private static final String FULL = "transaction_scored_event_v2_full_bounded_engine_intelligence.json";
    private static final String UNKNOWN_NESTED = "transaction_scored_event_v2_unknown_nested_engine_intelligence_fields.json";
    private static final String UNKNOWN_TOP_LEVEL = "transaction_scored_event_v2_unknown_top_level_field.json";
    private static final Set<String> KNOWN_FIXTURES = Set.of(OLD, MINIMAL, FULL, UNKNOWN_NESTED, UNKNOWN_TOP_LEVEL);

    private TransactionScoredEventFixtureLoader() {
    }

    static String oldWithoutEngineIntelligenceJson() {
        return readFixture(OLD);
    }

    static String minimalEngineIntelligenceJson() {
        return readFixture(MINIMAL);
    }

    static String fullBoundedEngineIntelligenceJson() {
        return readFixture(FULL);
    }

    static String unknownNestedEngineIntelligenceFieldsJson() {
        return readFixture(UNKNOWN_NESTED);
    }

    static String unknownTopLevelFieldJson() {
        return readFixture(UNKNOWN_TOP_LEVEL);
    }

    static String readFixture(String name) {
        if (!KNOWN_FIXTURES.contains(name)) {
            throw new IllegalArgumentException("TRANSACTION_SCORED_EVENT_FIXTURE_UNKNOWN");
        }
        try (InputStream stream = TransactionScoredEventFixtureLoader.class.getClassLoader().getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("TRANSACTION_SCORED_EVENT_FIXTURE_MISSING");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("TRANSACTION_SCORED_EVENT_FIXTURE_READ_FAILED", exception);
        }
    }
}
