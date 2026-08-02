package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudFeedbackMapperTest {

    private final FraudFeedbackMapper mapper = new FraudFeedbackMapper();

    @Test
    void downgradesAvailableEngineIntelligenceWhenComparisonSnapshotIsCorrupted() {
        FraudFeedbackRecord record = recordWithCorruptedComparisonSnapshot(EngineIntelligenceResponseStatus.AVAILABLE);

        FraudFeedbackResponse response = mapper.toResponse(record);

        assertThat(response.engineIntelligenceStatus()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
        assertThat(response.comparisonType()).isNull();
        assertThat(response.comparedEngineIds()).isEmpty();
        assertThat(response.agreementStatus()).isNull();
        assertThat(response.riskMismatchStatus()).isNull();
        assertThat(response.scoreDeltaBucket()).isNull();
    }

    @Test
    void keepsUnavailableEngineIntelligenceWhenComparisonSnapshotIsCorrupted() {
        FraudFeedbackRecord record = recordWithCorruptedComparisonSnapshot(EngineIntelligenceResponseStatus.UNAVAILABLE);

        FraudFeedbackResponse response = mapper.toResponse(record);

        assertThat(response.engineIntelligenceStatus()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
        assertThat(response.comparisonType()).isNull();
    }

    private static FraudFeedbackRecord recordWithCorruptedComparisonSnapshot(EngineIntelligenceResponseStatus status) {
        FraudFeedbackRecord record = new FraudFeedbackRecord();
        record.setEngineIntelligenceStatus(status);
        record.setComparisonType(EngineIntelligenceComparisonType.RULES_VS_ML);
        record.setComparedEngineIds(List.of("rules.primary"));
        record.setAgreementStatus(EngineIntelligenceAgreementStatus.AGREEMENT);
        record.setRiskMismatchStatus(EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL);
        record.setScoreDeltaBucket(EngineIntelligenceScoreDeltaBucket.NONE);
        return record;
    }
}
