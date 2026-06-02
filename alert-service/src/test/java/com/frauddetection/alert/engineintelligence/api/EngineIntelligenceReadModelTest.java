package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceReadModelTest {

    @Test
    void projectedNullListsBecomeEmptyLists() {
        EngineIntelligenceReadModel response = EngineIntelligenceReadModel.projected(
                "txn-null-lists",
                1,
                Instant.parse("2026-06-02T13:00:00Z"),
                null,
                null,
                null,
                null
        );

        assertThat(response.engines()).isEmpty();
        assertThat(response.diagnosticSignals()).isEmpty();
        assertThat(response.warnings()).isEmpty();
        assertThat(response.engineCount()).isZero();
        assertThat(response.diagnosticSignalCount()).isZero();
        assertThat(response.warningCount()).isZero();
    }

    @Test
    void engineReasonCodesNullBecomesEmptyList() {
        EngineIntelligenceEngineReadModel engine = engine(null);

        assertThat(engine.reasonCodes()).isEmpty();
    }

    @Test
    void listsAreDefensivelyCopied() {
        List<EngineIntelligenceEngineReadModel> engines = new ArrayList<>();
        List<String> reasonCodes = new ArrayList<>(List.of("HIGH_VELOCITY"));
        engines.add(engine(reasonCodes));

        EngineIntelligenceReadModel response = EngineIntelligenceReadModel.projected(
                "txn-copied-lists",
                1,
                Instant.parse("2026-06-02T13:00:00Z"),
                null,
                engines,
                List.of(),
                List.of()
        );
        engines.clear();
        reasonCodes.clear();

        assertThat(response.engines()).hasSize(1);
        assertThat(response.engines().getFirst().reasonCodes()).containsExactly("HIGH_VELOCITY");
    }

    @Test
    void returnedListsAreImmutable() {
        EngineIntelligenceEngineReadModel engine = engine(List.of("HIGH_VELOCITY"));
        EngineIntelligenceReadModel response = EngineIntelligenceReadModel.projected(
                "txn-immutable-lists",
                1,
                Instant.parse("2026-06-02T13:00:00Z"),
                null,
                List.of(engine),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> response.engines().add(engine))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> engine.reasonCodes().add("DEVICE_NOVELTY"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private EngineIntelligenceEngineReadModel engine(List<String> reasonCodes) {
        return new EngineIntelligenceEngineReadModel(
                "rules.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                null,
                EngineIntelligenceScoreBucket.NONE,
                reasonCodes
        );
    }
}
