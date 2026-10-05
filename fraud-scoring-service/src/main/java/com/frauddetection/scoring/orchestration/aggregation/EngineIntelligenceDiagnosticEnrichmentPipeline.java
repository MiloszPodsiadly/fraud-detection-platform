package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.scoring.domain.FraudScoringRequest;

import java.util.Optional;

public interface EngineIntelligenceDiagnosticEnrichmentPipeline {

    Optional<EngineIntelligenceEnrichmentResult> enrich(FraudScoringRequest scoringRequest);
}
