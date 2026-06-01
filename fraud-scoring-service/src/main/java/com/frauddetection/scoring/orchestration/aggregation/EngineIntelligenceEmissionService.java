package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public class EngineIntelligenceEmissionService {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligenceEmissionService.class);

    private final EngineIntelligenceEmissionProperties properties;
    private final ScoringContextFactory scoringContextFactory;
    private final ScoringProperties scoringProperties;
    private final FraudScoringOrchestrator orchestrator;
    private final FraudEngineAggregationService aggregationService;
    private final PublicEngineIntelligenceMapper mapper;
    private final Clock clock;

    public EngineIntelligenceEmissionService(
            EngineIntelligenceEmissionProperties properties,
            ScoringContextFactory scoringContextFactory,
            ScoringProperties scoringProperties,
            FraudScoringOrchestrator orchestrator,
            FraudEngineAggregationService aggregationService,
            PublicEngineIntelligenceMapper mapper
    ) {
        this(properties, scoringContextFactory, scoringProperties, orchestrator, aggregationService, mapper, Clock.systemUTC());
    }

    EngineIntelligenceEmissionService(
            EngineIntelligenceEmissionProperties properties,
            ScoringContextFactory scoringContextFactory,
            ScoringProperties scoringProperties,
            FraudScoringOrchestrator orchestrator,
            FraudEngineAggregationService aggregationService,
            PublicEngineIntelligenceMapper mapper,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.scoringContextFactory = Objects.requireNonNull(scoringContextFactory, "scoringContextFactory is required");
        this.scoringProperties = Objects.requireNonNull(scoringProperties, "scoringProperties is required");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator is required");
        this.aggregationService = Objects.requireNonNull(aggregationService, "aggregationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public Optional<EngineIntelligenceSummary> emitIfEnabled(FraudScoringRequest scoringRequest) {
        Objects.requireNonNull(scoringRequest, "scoringRequest is required");
        if (!properties.emitEnabled()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.map(aggregationService.aggregate(orchestrator.evaluate(
                    scoringContextFactory.from(scoringRequest, scoringProperties.mode(), clock.instant())
            ))));
        } catch (RuntimeException exception) {
            log.warn("Engine intelligence enrichment omitted.");
            return Optional.empty();
        }
    }

    public boolean emitEnabled() {
        return properties.emitEnabled();
    }
}
