package com.frauddetection.scoring.service;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class PlaceholderMlModelScoringClient implements MlModelScoringClient {

    @Override
    public MlModelOutput score(MlModelInput input) {
        return new MlModelOutput(
                false,
                0.0d,
                RiskLevel.LOW,
                "ml-placeholder",
                "unavailable",
                Instant.now(),
                List.of("ML_MODEL_UNAVAILABLE"),
                Map.of(
                        "modelAvailable", false,
                        "fallbackReason", "No ML model runtime is configured yet."
                ),
                Map.of(
                        "engineType", "ML_PLACEHOLDER",
                        "explanationType", "NO_MODEL_INFERENCE",
                        "modelAvailable", false,
                        "fallbackReason", "No ML model runtime is configured yet."
                ),
                "No ML model runtime is configured yet."
        );
    }
}
