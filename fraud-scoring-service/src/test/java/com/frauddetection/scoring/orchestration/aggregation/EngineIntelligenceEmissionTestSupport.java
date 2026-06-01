package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContextFactory;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class EngineIntelligenceEmissionTestSupport {

    private EngineIntelligenceEmissionTestSupport() {
    }

    static EngineIntelligenceEmissionService service(
            boolean enabled,
            EngineIntelligenceDiagnosticEnrichmentPipeline pipeline
    ) {
        return service(enabled, pipeline, new NoOpEngineIntelligenceEmissionMetrics());
    }

    static EngineIntelligenceEmissionService service(
            boolean enabled,
            EngineIntelligenceDiagnosticEnrichmentPipeline pipeline,
            EngineIntelligenceEmissionMetrics metrics
    ) {
        return new EngineIntelligenceEmissionService(
                new EngineIntelligenceEmissionProperties(enabled),
                provider(pipeline),
                metrics
        );
    }

    static EngineIntelligenceDiagnosticEnrichmentPipeline pipeline(
            FraudScoringOrchestrator orchestrator,
            FraudEngineAggregationService aggregationService,
            PublicEngineIntelligenceMapper mapper
    ) {
        return new OrchestratedEngineIntelligenceDiagnosticEnrichmentPipeline(
                new ScoringContextFactory(),
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED),
                orchestrator,
                aggregationService,
                mapper,
                Clock.systemUTC()
        );
    }

    static FraudScoringRequest request() {
        return FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
