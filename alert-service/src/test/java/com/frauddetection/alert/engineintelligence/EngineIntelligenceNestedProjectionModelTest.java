package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
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
    void nullReasonCodesBecomeEmptyList() {
        assertThat(engine(null).reasonCodes()).isEmpty();
    }

    @Test
    void engineProjectionRejectsNullRequiredField() {
        assertThatThrownBy(() -> new EngineIntelligenceEngineProjection(
                null,
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                List.of("HIGH_VELOCITY")
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void diagnosticProjectionRejectsNullReasonCode() {
        assertThatThrownBy(() -> new EngineIntelligenceDiagnosticSignalProjection(
                "rules.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void warningProjectionRejectsNullCode() {
        assertThatThrownBy(() -> new EngineIntelligenceWarningProjection(null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void warningProjectionRejectsNegativeCount() {
        assertThatThrownBy(() -> new EngineIntelligenceWarningProjection(
                EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED,
                -1
        )).isInstanceOf(IllegalArgumentException.class);
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
