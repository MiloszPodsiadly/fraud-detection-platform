package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.scoring.source.SourceOccurrencePublisher;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEnrichmentResult;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionOmissionReason;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionResult;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class TransactionFraudScoringService implements TransactionFraudScoringUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionFraudScoringService.class);

    private final FraudScoringEngine fraudScoringEngine;
    private final TransactionScoredEventMapper transactionScoredEventMapper;
    private final TransactionScoredEventPublisher transactionScoredEventPublisher;
    private final ScoringProperties scoringProperties;
    private final ScoringMetrics scoringMetrics;
    private final EngineIntelligenceEmissionService engineIntelligenceEmissionService;
    private final AnalystRecommendationService analystRecommendationService;
    private final SourceLineageAccess sourceLineage;
    private final SourceOccurrencePublisher sourceOccurrences;
    private final SourceOriginProofs originProofs;

    public TransactionFraudScoringService(
            FraudScoringEngine fraudScoringEngine,
            TransactionScoredEventMapper transactionScoredEventMapper,
            TransactionScoredEventPublisher transactionScoredEventPublisher,
            ScoringProperties scoringProperties,
            ScoringMetrics scoringMetrics,
            EngineIntelligenceEmissionService engineIntelligenceEmissionService,
            AnalystRecommendationService analystRecommendationService
    ) {
        this(fraudScoringEngine, transactionScoredEventMapper, transactionScoredEventPublisher, scoringProperties, scoringMetrics,
                engineIntelligenceEmissionService, analystRecommendationService, SourceLineageAccess.legacyOnly());
    }

    @Autowired
    public TransactionFraudScoringService(FraudScoringEngine engine, TransactionScoredEventMapper mapper,
                                          TransactionScoredEventPublisher publisher, ScoringProperties properties, ScoringMetrics metrics,
                                          EngineIntelligenceEmissionService emission, AnalystRecommendationService recommendations,
                                          ObjectProvider<SourceLineageAccess> lineage, ObjectProvider<SourceOccurrencePublisher> occurrences,
                                          ObjectProvider<SourceOriginProofs> originProofs) {
        this(engine, mapper, publisher, properties, metrics, emission, recommendations,
                lineage.getIfAvailable(SourceLineageAccess::disabled), occurrences.getIfAvailable(), originProofs.getIfAvailable());
    }

    public TransactionFraudScoringService(FraudScoringEngine fraudScoringEngine, TransactionScoredEventMapper transactionScoredEventMapper,
                                          TransactionScoredEventPublisher transactionScoredEventPublisher, ScoringProperties scoringProperties,
                                          ScoringMetrics scoringMetrics, EngineIntelligenceEmissionService engineIntelligenceEmissionService,
                                          AnalystRecommendationService analystRecommendationService, SourceLineageAccess lineage) {
        this(fraudScoringEngine, transactionScoredEventMapper, transactionScoredEventPublisher, scoringProperties, scoringMetrics,
                engineIntelligenceEmissionService, analystRecommendationService, lineage, null, null);
    }

    public TransactionFraudScoringService(FraudScoringEngine fraudScoringEngine, TransactionScoredEventMapper transactionScoredEventMapper,
                                          TransactionScoredEventPublisher transactionScoredEventPublisher, ScoringProperties scoringProperties,
                                          ScoringMetrics scoringMetrics, EngineIntelligenceEmissionService engineIntelligenceEmissionService,
                                          AnalystRecommendationService analystRecommendationService, SourceLineageAccess lineage,
                                          SourceOccurrencePublisher occurrences) {
        this(fraudScoringEngine, transactionScoredEventMapper, transactionScoredEventPublisher, scoringProperties, scoringMetrics,
                engineIntelligenceEmissionService, analystRecommendationService, lineage, occurrences, null);
    }

    public TransactionFraudScoringService(FraudScoringEngine fraudScoringEngine, TransactionScoredEventMapper transactionScoredEventMapper,
                                          TransactionScoredEventPublisher transactionScoredEventPublisher, ScoringProperties scoringProperties,
                                          ScoringMetrics scoringMetrics, EngineIntelligenceEmissionService engineIntelligenceEmissionService,
                                          AnalystRecommendationService analystRecommendationService, SourceLineageAccess lineage,
                                          SourceOccurrencePublisher occurrences, SourceOriginProofs originProofs) {
        this.fraudScoringEngine = fraudScoringEngine;
        this.transactionScoredEventMapper = transactionScoredEventMapper;
        this.transactionScoredEventPublisher = transactionScoredEventPublisher;
        this.scoringProperties = scoringProperties;
        this.scoringMetrics = scoringMetrics;
        this.engineIntelligenceEmissionService = engineIntelligenceEmissionService;
        this.analystRecommendationService = analystRecommendationService;
        this.sourceLineage = java.util.Objects.requireNonNull(lineage);
        this.sourceOccurrences = occurrences;
        this.originProofs = originProofs;
    }

    @Override
    public void score(TransactionEnrichedEvent event) {
        score(event, null, MongoSourceOccurrenceStore.INITIAL_RUN);
    }

    @Override
    public void score(TransactionEnrichedEvent event, String originProof) {
        score(event, originProof, MongoSourceOccurrenceStore.INITIAL_RUN);
    }

    /** Internal explicit rescore seam. No Kafka header or HTTP field selects a new run. Reusing its ID is a replay. */
    public void rescore(TransactionEnrichedEvent event, String runId) {
        if (sourceOccurrences == null || runId == null || !runId.matches("rescore:[A-Za-z0-9._:-]{1,119}")) {
            throw new IllegalArgumentException("SOURCE_RESCORE_RUN_REQUIRED");
        }
        score(event, null, runId);
    }

    private void score(TransactionEnrichedEvent event, String proof, String runId) {
        long startedAt = System.nanoTime();
        log.info("Started transaction fraud scoring.");

        try {
            OriginResolution origin = resolveOrigin(event, proof);
            if (!MongoSourceOccurrenceStore.INITIAL_RUN.equals(runId)
                    && (origin.legacy() || sourceOccurrences == null)) { throw new IllegalArgumentException("SOURCE_RESCORE_RUN_REQUIRED"); }
            FraudScoringRequest scoringRequest = FraudScoringRequest.from(event, origin.authorization());
            AtomicReference<FraudScoreResult> computed = new AtomicReference<>();
            Supplier<TransactionScoredEvent> compute = () -> {
                FraudScoreResult scoreResult = fraudScoringEngine.score(scoringRequest);
                computed.set(scoreResult);
                EngineIntelligenceEmissionResult engineIntelligence = engineIntelligence(scoringRequest);
                AnalystRecommendationResult analystRecommendation = analystRecommendation(scoreResult, engineIntelligence);
                return transactionScoredEventMapper.toEvent(
                        scoringRequest,
                        scoreResult,
                        summary(engineIntelligence),
                        mlPredictionEvidence(engineIntelligence),
                        mlPredictionEvidenceOmissionReason(engineIntelligence),
                        analystRecommendation
                );
            };
            TransactionScoredEvent scoredEvent;
            if (!origin.legacy() && sourceOccurrences != null && scoringRequest.sourceLineage().state() == SourceLineage.State.VERIFIED) {
                scoredEvent = sourceOccurrences.publish(event, runId, compute);
            } else {
                if (!origin.legacy() && sourceOccurrences != null) {
                    throw new IllegalStateException("SOURCE_SCORED_PUBLICATION_UNRESOLVED");
                }
                scoredEvent = compute.get();
                transactionScoredEventPublisher.publish(scoredEvent);
            }

            FraudScoreResult scoreResult = computed.get();
            if (scoreResult != null) {
                scoringMetrics.recordScoringRequest(
                        scoringProperties.mode(),
                        scoreResult.riskLevel(),
                        fallbackUsed(scoreResult),
                        true,
                        System.nanoTime() - startedAt
                );
            }

            log.atInfo()
                    .addKeyValue("riskLevel", scoredEvent.riskLevel())
                    .addKeyValue("fraudScore", scoredEvent.fraudScore())
                    .log("Completed transaction fraud scoring.");
        } catch (RuntimeException exception) {
            scoringMetrics.recordScoringRequest(
                    scoringProperties.mode(),
                    null,
                    false,
                    false,
                    System.nanoTime() - startedAt
            );
            throw exception;
        }
    }

    private OriginResolution resolveOrigin(TransactionEnrichedEvent event, String token) {
        if (originProofs != null) {
            SourceOriginProofs.Verification proof = originProofs.verifyEnriched(event, token);
            if (proof.status() == SourceOriginProofs.Status.INVALID) { throw new IllegalStateException("SOURCE_ORIGIN_UNRESOLVED"); }
            if (proof.status() == SourceOriginProofs.Status.VERIFIED) {
                if (proof.assertion() == SourceOriginProofs.Assertion.PROVEN_LEGACY) {
                    return new OriginResolution(true, SourceLineage.unavailable(SourceLineage.State.UNVERIFIED));
                }
                SourceLineage authority = sourceLineage.enriched(event);
                if (authority == null || authority.state() != SourceLineage.State.VERIFIED) {
                    throw new IllegalStateException("SOURCE_ORIGIN_UNRESOLVED");
                }
                return new OriginResolution(false, authority);
            }
        } else if (sourceLineage.isLegacyOnly()) {
            return new OriginResolution(true, SourceLineage.unavailable(SourceLineage.State.UNVERIFIED));
        }
        SourceLineage authority = sourceLineage.enriched(event);
        if (authority != null && authority.state() == SourceLineage.State.VERIFIED) { return new OriginResolution(false, authority); }
        throw new IllegalStateException("SOURCE_ORIGIN_UNRESOLVED");
    }

    private record OriginResolution(boolean legacy, SourceLineage authorization) { }

    private AnalystRecommendationResult analystRecommendation(
            FraudScoreResult scoreResult,
            EngineIntelligenceEmissionResult engineIntelligence
    ) {
        if (engineIntelligence.omissionReason().isPresent()
                && engineIntelligence.omissionReason().orElseThrow()
                != EngineIntelligenceEmissionOmissionReason.DISABLED) {
            return analystRecommendationService.unavailable();
        }
        try {
            return analystRecommendationService.recommend(scoreResult, summary(engineIntelligence));
        } catch (RuntimeException exception) {
            log.warn("Analyst recommendation enrichment omitted.", exception);
            return analystRecommendationService.unavailable();
        }
    }

    private EngineIntelligenceEmissionResult engineIntelligence(FraudScoringRequest scoringRequest) {
        try {
            return engineIntelligenceEmissionService.emitIfEnabled(scoringRequest);
        } catch (RuntimeException exception) {
            log.warn("Engine intelligence enrichment omitted.");
            return EngineIntelligenceEmissionResult.omitted(
                    EngineIntelligenceEmissionOmissionReason.UNKNOWN_FAILURE
            );
        }
    }

    private Optional<EngineIntelligenceSummary> summary(EngineIntelligenceEmissionResult emission) {
        return emission.enrichment().map(EngineIntelligenceEnrichmentResult::engineIntelligenceSummary);
    }

    private Optional<MlPredictionEvidence> mlPredictionEvidence(EngineIntelligenceEmissionResult emission) {
        return emission.enrichment().flatMap(EngineIntelligenceEnrichmentResult::mlPredictionEvidence);
    }

    private Optional<MlPredictionEvidenceOmissionReason> mlPredictionEvidenceOmissionReason(
            EngineIntelligenceEmissionResult emission
    ) {
        if (emission.enrichment().isPresent()) {
            return emission.enrichment().orElseThrow().mlPredictionEvidenceOmissionReason();
        }
        return emission.omissionReason()
                .map(EngineIntelligenceEmissionOmissionReason::toMlPredictionEvidenceOmissionReason);
    }

    private boolean fallbackUsed(FraudScoreResult result) {
        Object value = result.explanationMetadata().get("mlDiagnostics");
        if (!(value instanceof java.util.Map<?, ?> diagnostics)) {
            return false;
        }
        return Boolean.TRUE.equals(diagnostics.get("fallbackUsed"));
    }

}
