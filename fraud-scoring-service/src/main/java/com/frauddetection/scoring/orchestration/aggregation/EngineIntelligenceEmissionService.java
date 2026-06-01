package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class EngineIntelligenceEmissionService {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligenceEmissionService.class);

    private final EngineIntelligenceEmissionProperties properties;
    private final PublicEngineIntelligenceMapper mapper;

    public EngineIntelligenceEmissionService(EngineIntelligenceEmissionProperties properties) {
        this(properties, new PublicEngineIntelligenceMapper());
    }

    EngineIntelligenceEmissionService(
            EngineIntelligenceEmissionProperties properties,
            PublicEngineIntelligenceMapper mapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    public Optional<EngineIntelligenceSummary> mapIfEnabled(Supplier<FraudEngineAggregationResult> aggregationResult) {
        Objects.requireNonNull(aggregationResult, "aggregationResult is required");
        if (!properties.emitEnabled()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.map(aggregationResult.get()));
        } catch (RuntimeException exception) {
            log.warn("Engine intelligence enrichment omitted.");
            return Optional.empty();
        }
    }
}
