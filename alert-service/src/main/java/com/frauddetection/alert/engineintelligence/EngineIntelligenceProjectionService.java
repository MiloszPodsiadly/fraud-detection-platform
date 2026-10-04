package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceProjectionMetricReason;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class EngineIntelligenceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligenceProjectionService.class);

    private final EngineIntelligenceProjectionRepository repository;
    private final ScoredTransactionRepository scoredTransactionRepository;
    private final EngineIntelligenceProjectionMapper mapper;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public EngineIntelligenceProjectionService(
            EngineIntelligenceProjectionRepository repository,
            EngineIntelligenceProjectionMapper mapper,
            AlertServiceMetrics metrics,
            ScoredTransactionRepository scoredTransactionRepository
    ) {
        this(repository, mapper, metrics, scoredTransactionRepository, Clock.systemUTC());
    }

    EngineIntelligenceProjectionService(
            EngineIntelligenceProjectionRepository repository,
            EngineIntelligenceProjectionMapper mapper,
            AlertServiceMetrics metrics,
            ScoredTransactionRepository scoredTransactionRepository,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.scoredTransactionRepository = Objects.requireNonNull(
                scoredTransactionRepository,
                "scoredTransactionRepository is required"
        );
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EngineIntelligenceProjectionResult project(TransactionScoredEvent event) {
        return project(event, false, false);
    }

    @Transactional(transactionManager = "mongoTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public EngineIntelligenceProjectionResult projectCurrentOccurrence(TransactionScoredEvent event) {
        return project(event, true, true);
    }

    private EngineIntelligenceProjectionResult project(
            TransactionScoredEvent event,
            boolean storeRequired,
            boolean requireCurrentOccurrence
    ) {
        Instant startedAt = clock.instant();
        metrics.recordEngineIntelligenceProjectionAttempt();
        try {
            if (event == null) {
                EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                        EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE
                );
                metrics.recordEngineIntelligenceProjectionOmitted(EngineIntelligenceProjectionMetricReason.INVALID_PROJECTION_SHAPE);
                logOmission(result);
                return result;
            }
            if (event.engineIntelligence() == null) {
                EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                        EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_ABSENT
                );
                metrics.recordEngineIntelligenceProjectionOmitted(EngineIntelligenceProjectionMetricReason.ENGINE_INTELLIGENCE_ABSENT);
                return result;
            }

            if (requireCurrentOccurrence && !isCurrentOccurrence(event)) {
                EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                        EngineIntelligenceProjectionOmissionReason.SCORING_OCCURRENCE_NOT_CURRENT
                );
                metrics.recordEngineIntelligenceProjectionOmitted(
                        EngineIntelligenceProjectionMetricReason.STALE_OCCURRENCE
                );
                return result;
            }

            Instant createdAt = existingCreatedAt(event.transactionId(), event.eventId());
            EngineIntelligenceProjectionResult result = mapper.map(
                    event.transactionId(),
                    event.eventId(),
                    event.engineIntelligence(),
                    createdAt
            );
            if (result.projection().isPresent()) {
                save(result.projection().orElseThrow());
                metrics.recordEngineIntelligenceProjectionSuccess();
            } else {
                metrics.recordEngineIntelligenceProjectionOmitted(metricReason(result.omissionReason().orElse(null)));
            }
            logOmission(result);
            return result;
        } catch (EngineIntelligenceProjectionValidationException exception) {
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(exception.reason());
            metrics.recordEngineIntelligenceProjectionOmitted(metricReason(exception.reason()));
            logOmission(result);
            return result;
        } catch (ProjectionStoreUnavailableException exception) {
            metrics.recordEngineIntelligenceProjectionFailure(EngineIntelligenceProjectionMetricReason.STORE_UNAVAILABLE);
            if (storeRequired) {
                throw exception;
            }
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_PROJECTION_FAILED
            );
            logOmission(result);
            return result;
        } catch (RuntimeException exception) {
            metrics.recordEngineIntelligenceProjectionFailure(EngineIntelligenceProjectionMetricReason.UNKNOWN_FAILURE);
            if (storeRequired) {
                throw exception;
            }
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_PROJECTION_FAILED
            );
            logOmission(result);
            return result;
        } finally {
            metrics.recordEngineIntelligenceProjectionLatency(Duration.between(startedAt, clock.instant()));
        }
    }

    private void save(EngineIntelligenceProjection projection) {
        try {
            repository.save(projection);
        } catch (RuntimeException exception) {
            throw new ProjectionStoreUnavailableException(exception);
        }
    }

    private EngineIntelligenceProjectionMetricReason metricReason(EngineIntelligenceProjectionOmissionReason reason) {
        if (reason == null) {
            return EngineIntelligenceProjectionMetricReason.UNKNOWN_FAILURE;
        }
        return switch (reason) {
            case ENGINE_INTELLIGENCE_ABSENT -> EngineIntelligenceProjectionMetricReason.ENGINE_INTELLIGENCE_ABSENT;
            case ENGINE_INTELLIGENCE_INVALID_SHAPE,
                 ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION,
                 ENGINE_INTELLIGENCE_OVERSIZED,
                 ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED -> EngineIntelligenceProjectionMetricReason.INVALID_PROJECTION_SHAPE;
            case SCORING_OCCURRENCE_NOT_CURRENT -> EngineIntelligenceProjectionMetricReason.STALE_OCCURRENCE;
            case ENGINE_INTELLIGENCE_PROJECTION_FAILED -> EngineIntelligenceProjectionMetricReason.UNKNOWN_FAILURE;
        };
    }

    private boolean isCurrentOccurrence(TransactionScoredEvent event) {
        ScoredTransactionDocument current;
        try {
            current = scoredTransactionRepository.findById(event.transactionId()).orElse(null);
        } catch (RuntimeException exception) {
            throw new ProjectionStoreUnavailableException(exception);
        }
        if (current == null) {
            throw new CurrentScoringOccurrencePendingException();
        }
        ScoringOccurrenceOwnership ownership;
        try {
            ownership = ScoringOccurrenceOwnership.fromPersistedIdentity(
                    current.getSourceEventId(),
                    current.getSourceEventCreatedAt(),
                    current.getSourceEventCreatedAtEpochSecond(),
                    current.getSourceEventCreatedAtNano(),
                    current.getSourceEventFingerprint()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("SCORING_OCCURRENCE_IDENTITY_INVALID", exception);
        }
        if (ownership.state() != ScoringOccurrenceOwnership.State.AUTHORITATIVE) {
            throw new CurrentScoringOccurrencePendingException();
        }
        if (ownership.sourceEventId().equals(event.eventId())) {
            if (!Objects.equals(current.getSourceEventFingerprint(), ScoringOccurrenceFingerprint.from(event))) {
                throw new IllegalStateException("SCORING_OCCURRENCE_PAYLOAD_CONFLICT");
            }
            return true;
        }
        int timestampOrder = event.createdAt().compareTo(ownership.sourceEventCreatedAt());
        int occurrenceOrder = timestampOrder != 0
                ? timestampOrder
                : event.eventId().compareTo(ownership.sourceEventId());
        if (occurrenceOrder > 0) {
            throw new CurrentScoringOccurrencePendingException();
        }
        return false;
    }

    private Instant existingCreatedAt(String transactionId, String sourceEventId) {
        try {
            return repository.findById(transactionId)
                    .filter(existing -> Objects.equals(existing.getSourceEventId(), sourceEventId))
                    .map(EngineIntelligenceProjection::getCreatedAt)
                    .orElse(null);
        } catch (RuntimeException exception) {
            throw new ProjectionStoreUnavailableException(exception);
        }
    }

    static final class ProjectionStoreUnavailableException extends RuntimeException {

        private ProjectionStoreUnavailableException(RuntimeException cause) {
            super("ENGINE_INTELLIGENCE_PROJECTION_STORE_UNAVAILABLE", cause);
        }
    }

    static final class CurrentScoringOccurrencePendingException extends RuntimeException {

        private CurrentScoringOccurrencePendingException() {
            super("CURRENT_SCORING_OCCURRENCE_PENDING");
        }
    }

    private void logOmission(EngineIntelligenceProjectionResult result) {
        result.omissionReason().ifPresent(reason -> log.atWarn()
                .addKeyValue("reason", reason)
                .log("Engine intelligence internal projection omitted."));
    }
}
