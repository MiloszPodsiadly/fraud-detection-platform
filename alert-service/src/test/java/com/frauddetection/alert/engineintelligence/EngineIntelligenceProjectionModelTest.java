package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceProjectionModelTest {

    private static final Instant NOW = Instant.parse("2026-06-02T08:00:00Z");

    @Test
    void nullListsBecomeEmptyLists() {
        EngineIntelligenceProjection projection = projection(null, null, null);

        assertThat(projection.getEngines()).isEmpty();
        assertThat(projection.getDiagnosticSignals()).isEmpty();
        assertThat(projection.getWarnings()).isEmpty();
        assertThat(projection.getEngineCount()).isZero();
        assertThat(projection.getDiagnosticSignalCount()).isZero();
        assertThat(projection.getWarningCount()).isZero();
    }

    @Test
    void countsAreDerivedFromCopiedLists() {
        EngineIntelligenceProjection projection = projection(
                List.of(engine()),
                List.of(signal()),
                List.of(warning())
        );

        assertThat(projection.getEngineCount()).isEqualTo(projection.getEngines().size());
        assertThat(projection.getDiagnosticSignalCount()).isEqualTo(projection.getDiagnosticSignals().size());
        assertThat(projection.getWarningCount()).isEqualTo(projection.getWarnings().size());
    }

    @Test
    void inputListsAreDefensivelyCopied() {
        List<EngineIntelligenceEngineProjection> engines = new ArrayList<>(List.of(engine()));
        EngineIntelligenceProjection projection = projection(engines, List.of(), List.of());

        engines.clear();

        assertThat(projection.getEngines()).hasSize(1);
    }

    @Test
    void projectionListsAreImmutable() {
        EngineIntelligenceProjection projection = projection(List.of(engine()), List.of(), List.of());

        assertThatThrownBy(() -> projection.getEngines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullListProjectionDoesNotThrow() {
        assertThatCode(() -> projection(null, null, null)).doesNotThrowAnyException();
    }

    private EngineIntelligenceProjection projection(
            List<EngineIntelligenceEngineProjection> engines,
            List<EngineIntelligenceDiagnosticSignalProjection> signals,
            List<EngineIntelligenceWarningProjection> warnings
    ) {
        return new EngineIntelligenceProjection(
                "txn-fdp95-model",
                1,
                NOW,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA,
                EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                EngineIntelligenceScoreDeltaBucket.UNAVAILABLE,
                engines,
                signals,
                warnings,
                NOW,
                NOW
        );
    }

    private EngineIntelligenceEngineProjection engine() {
        return new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy())
                .map("txn-fdp95-model", EngineIntelligenceProjectionTestFixtures.minimalSummary(), null)
                .projection()
                .orElseThrow()
                .getEngines()
                .getFirst();
    }

    private EngineIntelligenceDiagnosticSignalProjection signal() {
        return new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy())
                .map("txn-fdp95-model", EngineIntelligenceProjectionTestFixtures.fullSummary(), null)
                .projection()
                .orElseThrow()
                .getDiagnosticSignals()
                .getFirst();
    }

    private EngineIntelligenceWarningProjection warning() {
        return new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy())
                .map("txn-fdp95-model", EngineIntelligenceProjectionTestFixtures.fullSummary(), null)
                .projection()
                .orElseThrow()
                .getWarnings()
                .getFirst();
    }
}
