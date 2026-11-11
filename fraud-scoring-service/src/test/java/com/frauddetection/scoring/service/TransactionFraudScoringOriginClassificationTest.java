package com.frauddetection.scoring.service;

import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.events.source.SourceOriginProperties;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionOmissionReason;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.frauddetection.common.events.source.SourceLineage.State.UNAVAILABLE;
import static com.frauddetection.common.events.source.SourceOriginProofs.Assertion.KNOWN_ATTESTED;
import static com.frauddetection.common.events.source.SourceOriginProofs.Assertion.PROVEN_LEGACY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionFraudScoringOriginClassificationTest {
    private static final SourceOriginProofs PROOFS = new SourceOriginProofs(new SourceOriginProperties(true, "origin-key-1",
            Map.of("origin-key-1", Base64.getEncoder().encodeToString(new byte[32]))));

    @Test void verifiedLegacyIngressScoresWhenTheOptionalSidecarIsUnavailable() {
        var event = TransactionFixtures.enrichedTransaction().build();
        var request = FraudScoringRequest.from(event, SourceLineage.unavailable(SourceLineage.State.UNVERIFIED));
        var engine = mock(FraudScoringEngine.class);
        var mapper = mock(TransactionScoredEventMapper.class);
        var publisher = mock(TransactionScoredEventPublisher.class);
        var lineage = mock(SourceLineageAccess.class);
        var emission = mock(com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService.class);
        var score = new FraudScoreResult(.12d, com.frauddetection.common.events.enums.RiskLevel.LOW, "RULE_BASED",
                "rules", "v1", Instant.parse("2026-11-09T10:00:00Z"), List.of(), Map.of(), event.featureSnapshot(), Map.of(), true);
        var scored = TransactionFixtures.scoredTransaction().build();
        when(engine.score(request)).thenReturn(score);
        when(emission.emitIfEnabled(request)).thenReturn(EngineIntelligenceEmissionResult.omitted(EngineIntelligenceEmissionOmissionReason.DISABLED));
        when(mapper.toEvent(any(), any(), any(), any(), any(), any())).thenReturn(scored);
        var service = service(engine, mapper, publisher, lineage, emission);

        service.score(event, PROOFS.signEnriched(event, PROVEN_LEGACY));

        verify(lineage, never()).enriched(any());
        verify(engine).score(request);
        verify(publisher).publish(scored);
    }

    @Test void unknownOriginAndAttestedOriginWithoutCurrentLineageBothFailClosed() {
        var event = TransactionFixtures.enrichedTransaction().build();
        var lineage = mock(SourceLineageAccess.class);
        when(lineage.enriched(event)).thenReturn(SourceLineage.unavailable(UNAVAILABLE));
        var engine = mock(FraudScoringEngine.class);
        var mapper = mock(TransactionScoredEventMapper.class);
        var publisher = mock(TransactionScoredEventPublisher.class);
        var emission = mock(com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService.class);
        var service = service(engine, mapper, publisher, lineage, emission);

        assertThatThrownBy(() -> service.score(event, null)).hasMessage("SOURCE_ORIGIN_UNRESOLVED");
        assertThatThrownBy(() -> service.score(event, PROOFS.signEnriched(event, KNOWN_ATTESTED)))
                .hasMessage("SOURCE_ORIGIN_UNRESOLVED");

        verifyNoInteractions(engine, mapper, publisher, emission);
    }

    @Test void invalidLegacyProofCannotDowngradeToSidecarLookupOrScoring() {
        var event = TransactionFixtures.enrichedTransaction().build();
        var other = new com.frauddetection.common.events.contract.TransactionEnrichedEvent(event.eventId(), event.transactionId(),
                event.correlationId(), "different-customer", event.accountId(), event.createdAt(), event.transactionTimestamp(),
                event.transactionAmount(), event.merchantInfo(), event.deviceInfo(), event.locationInfo(), event.customerContext(),
                event.featureSnapshot());
        var lineage = mock(SourceLineageAccess.class);
        when(lineage.enriched(event)).thenReturn(SourceLineage.unavailable(UNAVAILABLE));
        var engine = mock(FraudScoringEngine.class);
        var mapper = mock(TransactionScoredEventMapper.class);
        var publisher = mock(TransactionScoredEventPublisher.class);
        var emission = mock(com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService.class);
        var service = service(engine, mapper, publisher, lineage, emission);

        assertThatThrownBy(() -> service.score(event, PROOFS.signEnriched(other, PROVEN_LEGACY)))
                .hasMessage("SOURCE_ORIGIN_UNRESOLVED");

        verify(lineage, never()).enriched(any());
        verifyNoInteractions(engine, mapper, publisher, emission);
    }

    private static TransactionFraudScoringService service(FraudScoringEngine engine, TransactionScoredEventMapper mapper,
                                                           TransactionScoredEventPublisher publisher, SourceLineageAccess lineage,
                                                           com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService emission) {
        return new TransactionFraudScoringService(engine, mapper, publisher,
                new ScoringProperties(.75d, .90d, ScoringMode.RULE_BASED), new ScoringMetrics(new SimpleMeterRegistry()),
                emission, new AnalystRecommendationService(), lineage, null, PROOFS);
    }
}
