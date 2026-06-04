package com.frauddetection.alert.engineintelligence.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class EngineIntelligenceFeedbackRequest {

    private final String feedbackType;
    private final String usefulness;
    private final String accuracyAssessment;
    private final Boolean engineIntelligenceAvailable;
    private final List<String> selectedReasonCodes;
    private final List<String> unknownFields = new ArrayList<>();

    @JsonCreator
    public EngineIntelligenceFeedbackRequest(
            @JsonProperty("feedbackType") String feedbackType,
            @JsonProperty("usefulness") String usefulness,
            @JsonProperty("accuracyAssessment") String accuracyAssessment,
            @JsonProperty("engineIntelligenceAvailable") Boolean engineIntelligenceAvailable,
            @JsonProperty("selectedReasonCodes") List<String> selectedReasonCodes
    ) {
        this.feedbackType = feedbackType;
        this.usefulness = usefulness;
        this.accuracyAssessment = accuracyAssessment;
        this.engineIntelligenceAvailable = engineIntelligenceAvailable;
        this.selectedReasonCodes = selectedReasonCodes == null ? List.of() : new ArrayList<>(selectedReasonCodes);
    }

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        unknownFields.add(name);
    }

    public String feedbackType() {
        return feedbackType;
    }

    public String usefulness() {
        return usefulness;
    }

    public String accuracyAssessment() {
        return accuracyAssessment;
    }

    public Boolean engineIntelligenceAvailable() {
        return engineIntelligenceAvailable;
    }

    public List<String> selectedReasonCodes() {
        return selectedReasonCodes;
    }

    public List<String> unknownFields() {
        return List.copyOf(unknownFields);
    }
}
