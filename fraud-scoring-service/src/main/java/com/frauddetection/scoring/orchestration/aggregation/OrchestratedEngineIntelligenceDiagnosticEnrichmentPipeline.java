package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public final class OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline
        implements EngineIntelligenceDiagnosticEnrichmentPipeline {

    private final ScoringContextFactory scoringContextFactory;
    private final ScoringProperties scoringProperties;
    private final FraudScoringOrchestrator orchestrator;
    private final FraudEngineAggregationService aggregationService;
    private final PublicEngineIntelligenceMapper mapper;
    private final Clock clock;

    public OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline(
            ScoringContextFactory scoringContextFactory,
            ScoringProperties scoringProperties,
            FraudScoringOrchestrator orchestrator,
            FraudEngineAggregationService aggregationService,
            PublicEngineIntelligenceMapper mapper,
            Clock clock
    ) {
        this.scoringContextFactory = Objects.requireNonNull(scoringContextFactory, "scoringContextFactory is required");
        this.scoringProperties = Objects.requireNonNull(scoringProperties, "scoringProperties is required");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator is required");
        this.aggregationService = Objects.requireNonNull(aggregationService, "aggregationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public Optional<EngineIntelligenceSummary> enrich(FraudScoringRequest scoringRequest) {
        Objects.requireNonNull(scoringRequest, "scoringRequest is required");
        return Optional.of(mapper.map(aggregationService.aggregate(orchestrator.evaluate(
                scoringContextFactory.from(scoringRequest, scoringProperties.mode(), clock.instant())
        ))));
    }
}
