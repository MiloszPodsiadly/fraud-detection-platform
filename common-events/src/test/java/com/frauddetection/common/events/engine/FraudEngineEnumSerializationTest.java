package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineEnumSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enumSerializationUsesStableNames() throws Exception {
        assertThat(objectMapper.writeValueAsString(FraudEngineType.RULES)).isEqualTo("\"RULES\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineType.ML_MODEL)).isEqualTo("\"ML_MODEL\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineStatus.AVAILABLE)).isEqualTo("\"AVAILABLE\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineStatus.UNAVAILABLE)).isEqualTo("\"UNAVAILABLE\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineConfidence.LOW)).isEqualTo("\"LOW\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineConfidence.MEDIUM)).isEqualTo("\"MEDIUM\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineConfidence.HIGH)).isEqualTo("\"HIGH\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineConfidence.UNKNOWN)).isEqualTo("\"UNKNOWN\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineContributionDirection.INCREASES_RISK))
                .isEqualTo("\"INCREASES_RISK\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineEvidenceType.MODEL_EXPLANATION))
                .isEqualTo("\"MODEL_EXPLANATION\"");
        assertThat(objectMapper.writeValueAsString(FraudEngineEvidenceStatus.AVAILABLE)).isEqualTo("\"AVAILABLE\"");
    }
}
