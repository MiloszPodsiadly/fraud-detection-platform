package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.domain.FraudScoringRequest;

import java.util.Optional;

public interface EngineIntelligenceDiagnosticEnrichmentPipeline {

    Optional<EngineIntelligenceSummary> enrich(FraudScoringRequest scoringRequest);
}
