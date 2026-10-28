package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceNestedProjectionModelTest {

    @Test
    void engineProjectionDefensivelyCopiesReasonCodes() {
        List<String> reasonCodes = new ArrayList<>(List.of("HIGH_VELOCITY"));
        EngineIntelligenceEngineProjection projection = engine(reasonCodes);

        reasonCodes.clear();

        assertThat(projection.reasonCodes()).containsExactly("HIGH_VELOCITY");
    }

    @Test
    void nestedProjectionReasonCodesImmutable() {
        EngineIntelligenceEngineProjection projection = engine(List.of("HIGH_VELOCITY"));

        assertThatThrownBy(() -> projection.reasonCodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullReasonCodesRemainMissing() {
        assertThat(engine(null).reasonCodes()).isNull();
    }

    @Test
    void nestedProjectionModelsPreserveMissingValuesForCanonicalValidation() {
        EngineIntelligenceEngineProjection engineProjection = new EngineIntelligenceEngineProjection(
                null, null, null, null, null, null);
        EngineIntelligenceDiagnosticSignalProjection signalProjection = new EngineIntelligenceDiagnosticSignalProjection(
                null, null, null, null, null, null, null);
        EngineIntelligenceWarningProjection warningProjection = new EngineIntelligenceWarningProjection(null, -1);

        assertThat(engineProjection.engineId()).isNull();
        assertThat(engineProjection.reasonCodes()).isNull();
        assertThat(signalProjection.engineId()).isNull();
        assertThat(signalProjection.reasonCode()).isNull();
        assertThat(warningProjection.warningCode()).isNull();
        assertThat(warningProjection.count()).isEqualTo(-1);
    }

    private EngineIntelligenceEngineProjection engine(List<String> reasonCodes) {
        return new EngineIntelligenceEngineProjection(
                "rules.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                reasonCodes
        );
    }
}
