package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.engineintelligence.observability.MlPredictionEvidenceProjectionMetricReason;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mapping.MappingException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class MlPredictionEvidenceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(MlPredictionEvidenceProjectionService.class);

    private final MlPredictionEvidenceProjectionRepository repository;
    private final EngineIntelligenceProjectionPolicy policy;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public MlPredictionEvidenceProjectionService(
            MlPredictionEvidenceProjectionRepository repository,
            EngineIntelligenceProjectionPolicy policy,
            AlertServiceMetrics metrics
    ) {
        this(repository, policy, metrics, Clock.systemUTC());
    }

    MlPredictionEvidenceProjectionService(
            MlPredictionEvidenceProjectionRepository repository,
            EngineIntelligenceProjectionPolicy policy,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.policy = Objects.requireNonNull(policy, "policy is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public MlPredictionEvidenceProjectionResult project(TransactionScoredEvent event) {
        Instant startedAt = clock.instant();
        metrics.recordMlPredictionEvidenceProjectionAttempt();
        try {
            if (event == null) {
                return failure(MlPredictionEvidenceProjectionReason.INVALID_EVIDENCE);
            }
            MlPredictionEvidenceProjection projection = validatedProjection(event);
            try {
                repository.insert(projection);
                if (projection.hasEvidence()) {
                    metrics.recordMlPredictionEvidenceProjectionSuccess();
                    return MlPredictionEvidenceProjectionResult.projected();
                }
                metrics.recordMlPredictionEvidenceProjectionOmitted(
                        MlPredictionEvidenceProjectionMetricReason.EVIDENCE_ABSENT
                );
                return MlPredictionEvidenceProjectionResult.omitted(
                        MlPredictionEvidenceProjectionReason.EVIDENCE_ABSENT
                );
            } catch (DuplicateKeyException duplicate) {
                return classifyReplay(projection);
            }
        } catch (MlPredictionEvidenceProjectionShapeException | EngineIntelligenceProjectionValidationException exception) {
            return failure(MlPredictionEvidenceProjectionReason.INVALID_EVIDENCE);
        } catch (MappingException exception) {
            return failure(MlPredictionEvidenceProjectionReason.INVALID_STORED_SHAPE);
        } catch (DataAccessException exception) {
            return failure(MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            if (hasCause(exception, MlPredictionEvidenceProjectionShapeException.class)) {
                return failure(MlPredictionEvidenceProjectionReason.INVALID_STORED_SHAPE);
            }
            return failure(MlPredictionEvidenceProjectionReason.UNKNOWN_FAILURE);
        } finally {
            metrics.recordMlPredictionEvidenceProjectionLatency(Duration.between(startedAt, clock.instant()));
        }
    }

    private MlPredictionEvidenceProjection validatedProjection(TransactionScoredEvent event) {
        if (event.createdAt() == null) {
            throw new MlPredictionEvidenceProjectionShapeException();
        }
        String sourceEventId = policy.validatedSourceEventId(event.eventId());
        String transactionId = policy.validatedTransactionId(event.transactionId());
        String correlationId = policy.validatedCorrelationId(event.correlationId());
        if (event.mlPredictionEvidence() != null) {
            MlPredictionEvidence evidence = policy.validatedEvidenceCopy(event.mlPredictionEvidence());
            return MlPredictionEvidenceProjection.create(
                    sourceEventId,
                    transactionId,
                    correlationId,
                    event.createdAt(),
                    evidence,
                    clock.instant()
            );
        }
        if (event.mlPredictionEvidenceOmissionReason() == null) {
            throw new MlPredictionEvidenceProjectionShapeException();
        }
        return MlPredictionEvidenceProjection.omitted(
                sourceEventId,
                transactionId,
                correlationId,
                event.createdAt(),
                event.mlPredictionEvidenceOmissionReason(),
                clock.instant()
        );
    }

    private MlPredictionEvidenceProjectionResult classifyReplay(MlPredictionEvidenceProjection candidate) {
        MlPredictionEvidenceProjection stored = repository.findById(candidate.getSourceEventId()).orElse(null);
        if (candidate.sameAuthoritativeOccurrence(stored)) {
            metrics.recordMlPredictionEvidenceProjectionIdempotentReplay();
            return MlPredictionEvidenceProjectionResult.idempotentReplay();
        }
        return failure(MlPredictionEvidenceProjectionReason.REPLAY_CONFLICT);
    }

    private MlPredictionEvidenceProjectionResult failure(MlPredictionEvidenceProjectionReason reason) {
        metrics.recordMlPredictionEvidenceProjectionFailure(metricReason(reason));
        log.atWarn()
                .addKeyValue("reason", reason)
                .log("ML prediction evidence projection failed.");
        return MlPredictionEvidenceProjectionResult.failed(reason);
    }

    private MlPredictionEvidenceProjectionMetricReason metricReason(MlPredictionEvidenceProjectionReason reason) {
        return switch (reason) {
            case EVIDENCE_ABSENT -> MlPredictionEvidenceProjectionMetricReason.EVIDENCE_ABSENT;
            case INVALID_EVIDENCE -> MlPredictionEvidenceProjectionMetricReason.INVALID_EVIDENCE;
            case REPLAY_CONFLICT -> MlPredictionEvidenceProjectionMetricReason.REPLAY_CONFLICT;
            case INVALID_STORED_SHAPE -> MlPredictionEvidenceProjectionMetricReason.INVALID_STORED_SHAPE;
            case STORE_UNAVAILABLE -> MlPredictionEvidenceProjectionMetricReason.STORE_UNAVAILABLE;
            case UNKNOWN_FAILURE -> MlPredictionEvidenceProjectionMetricReason.UNKNOWN_FAILURE;
        };
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
