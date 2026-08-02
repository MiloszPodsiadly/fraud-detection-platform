package com.frauddetection.common.events.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class TransactionScoredEventFixtureLoader {

    private static final String ROOT = "fixtures/transaction-scored-event/";
    private static final String OLD = "transaction_scored_event_v1_without_engine_intelligence.json";
    private static final String LEGACY_V1_ENGINE_INTELLIGENCE =
            "transaction_scored_event_v1_legacy_engine_intelligence_comparison.json";
    private static final String EXPLICIT_V1_ENGINE_INTELLIGENCE =
            "transaction_scored_event_v1_explicit_engine_intelligence_comparison.json";
    private static final String UNKNOWN_ADDITIVE_V1_ENGINE_INTELLIGENCE =
            "transaction_scored_event_v1_unknown_additive_engine_intelligence_fields.json";
    private static final String PARTIAL_COMPARISON_TYPE_ONLY =
            "transaction_scored_event_v1_partial_comparison_type_only.json";
    private static final String PARTIAL_COMPARED_ENGINE_IDS_ONLY =
            "transaction_scored_event_v1_partial_compared_engine_ids_only.json";
    private static final String WRONG_COMPARISON_TYPE =
            "transaction_scored_event_v1_wrong_comparison_type.json";
    private static final String REVERSED_COMPARISON_ENGINE_IDS =
            "transaction_scored_event_v1_reversed_comparison_engine_ids.json";
    private static final String VELOCITY_COMPARISON_ENGINE_IDS =
            "transaction_scored_event_v1_velocity_comparison_engine_ids.json";
    private static final String UNKNOWN_COMPARISON_ENGINE_IDS =
            "transaction_scored_event_v1_unknown_comparison_engine_ids.json";
    private static final String MINIMAL = "transaction_scored_event_v2_minimal_engine_intelligence.json";
    private static final String FULL = "transaction_scored_event_v2_full_bounded_engine_intelligence.json";
    private static final String UNKNOWN_NESTED = "transaction_scored_event_v2_unknown_nested_engine_intelligence_fields.json";
    private static final String UNKNOWN_TOP_LEVEL = "transaction_scored_event_v2_unknown_top_level_field.json";
    private static final Set<String> KNOWN_FIXTURES = Set.of(
            OLD,
            LEGACY_V1_ENGINE_INTELLIGENCE,
            EXPLICIT_V1_ENGINE_INTELLIGENCE,
            UNKNOWN_ADDITIVE_V1_ENGINE_INTELLIGENCE,
            PARTIAL_COMPARISON_TYPE_ONLY,
            PARTIAL_COMPARED_ENGINE_IDS_ONLY,
            WRONG_COMPARISON_TYPE,
            REVERSED_COMPARISON_ENGINE_IDS,
            VELOCITY_COMPARISON_ENGINE_IDS,
            UNKNOWN_COMPARISON_ENGINE_IDS,
            MINIMAL,
            FULL,
            UNKNOWN_NESTED,
            UNKNOWN_TOP_LEVEL
    );

    private TransactionScoredEventFixtureLoader() {
    }

    static String oldWithoutEngineIntelligenceJson() {
        return readFixture(OLD);
    }

    static String legacyV1EngineIntelligenceJson() {
        return readFixture(LEGACY_V1_ENGINE_INTELLIGENCE);
    }

    static String explicitV1EngineIntelligenceJson() {
        return readFixture(EXPLICIT_V1_ENGINE_INTELLIGENCE);
    }

    static String unknownAdditiveV1EngineIntelligenceJson() {
        return readFixture(UNKNOWN_ADDITIVE_V1_ENGINE_INTELLIGENCE);
    }

    static String partialComparisonTypeOnlyJson() {
        return readFixture(PARTIAL_COMPARISON_TYPE_ONLY);
    }

    static String partialComparedEngineIdsOnlyJson() {
        return readFixture(PARTIAL_COMPARED_ENGINE_IDS_ONLY);
    }

    static String wrongComparisonTypeJson() {
        return readFixture(WRONG_COMPARISON_TYPE);
    }

    static String reversedComparisonEngineIdsJson() {
        return readFixture(REVERSED_COMPARISON_ENGINE_IDS);
    }

    static String velocityComparisonEngineIdsJson() {
        return readFixture(VELOCITY_COMPARISON_ENGINE_IDS);
    }

    static String unknownComparisonEngineIdsJson() {
        return readFixture(UNKNOWN_COMPARISON_ENGINE_IDS);
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
