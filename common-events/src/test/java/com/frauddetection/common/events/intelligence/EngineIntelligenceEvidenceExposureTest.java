package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceEvidenceExposureTest {

    @Test
    void publicDtoDoesNotHaveEvidenceTitleField() {
        assertNoPublicField("evidenceTitle");
    }

    @Test
    void publicDtoDoesNotHaveEvidenceDescriptionField() {
        assertNoPublicField("evidenceDescription");
    }

    @Test
    void serializedEngineIntelligenceDoesNotContainEvidenceTitle() throws Exception {
        assertThat(json()).doesNotContain("evidenceTitle");
    }

    @Test
    void serializedEngineIntelligenceDoesNotContainEvidenceDescription() throws Exception {
        assertThat(json()).doesNotContain("evidenceDescription");
    }

    @Test
    void rawEvidenceTextIsNotSerialized() throws Exception {
        assertThat(json()).doesNotContain("rawEvidence", "description", "displayText");
    }

    @Test
    void reasonCodeMayBeSerializedIfAllowlisted() throws Exception {
        assertThat(json()).contains("\"reasonCode\":\"HIGH_VELOCITY\"");
    }

    @Test
    void signalCategoryMayBeSerialized() throws Exception {
        assertThat(json()).contains("\"signalCategory\":\"FRAUD_SIGNAL\"");
    }

    private void assertNoPublicField(String field) {
        assertThat(Arrays.stream(EngineIntelligenceDiagnosticSignal.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain(field);
    }

    private String json() throws Exception {
        return EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.summary());
    }
}
