package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceProjectionMetricReason;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class EngineIntelligenceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligenceProjectionService.class);

    private final EngineIntelligenceProjectionRepository repository;
    private final EngineIntelligenceProjectionMapper mapper;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public EngineIntelligenceProjectionService(
            EngineIntelligenceProjectionRepository repository,
            EngineIntelligenceProjectionMapper mapper,
            AlertServiceMetrics metrics
    ) {
        this(repository, mapper, metrics, Clock.systemUTC());
    }

    EngineIntelligenceProjectionService(
            EngineIntelligenceProjectionRepository repository,
            EngineIntelligenceProjectionMapper mapper,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EngineIntelligenceProjectionResult project(TransactionScoredEvent event) {
        Instant startedAt = clock.instant();
        metrics.recordEngineIntelligenceProjectionAttempt();
        if (event == null) {
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE
            );
            metrics.recordEngineIntelligenceProjectionOmitted(EngineIntelligenceProjectionMetricReason.INVALID_PROJECTION_SHAPE);
            metrics.recordEngineIntelligenceProjectionLatency(Duration.between(startedAt, clock.instant()));
            logOmission(result);
            return result;
        }
        if (event.engineIntelligence() == null) {
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_ABSENT
            );
            metrics.recordEngineIntelligenceProjectionOmitted(EngineIntelligenceProjectionMetricReason.ENGINE_INTELLIGENCE_ABSENT);
            metrics.recordEngineIntelligenceProjectionLatency(Duration.between(startedAt, clock.instant()));
            return result;
        }

        try {
            Instant createdAt = existingCreatedAt(event.transactionId());
            EngineIntelligenceProjectionResult result = mapper.map(
                    event.transactionId(),
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
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_PROJECTION_FAILED
            );
            metrics.recordEngineIntelligenceProjectionFailure(EngineIntelligenceProjectionMetricReason.STORE_UNAVAILABLE);
            logOmission(result);
            return result;
        } catch (RuntimeException exception) {
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_PROJECTION_FAILED
            );
            metrics.recordEngineIntelligenceProjectionFailure(EngineIntelligenceProjectionMetricReason.UNKNOWN_FAILURE);
            logOmission(result);
            return result;
        } finally {
            metrics.recordEngineIntelligenceProjectionLatency(Duration.between(startedAt, clock.instant()));
        }
    }

    private Instant existingCreatedAt(String transactionId) {
        try {
            return repository.findById(transactionId)
                    .map(EngineIntelligenceProjection::getCreatedAt)
                    .orElse(null);
        } catch (RuntimeException exception) {
            throw new ProjectionStoreUnavailableException(exception);
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
            case ENGINE_INTELLIGENCE_PROJECTION_FAILED -> EngineIntelligenceProjectionMetricReason.UNKNOWN_FAILURE;
        };
    }

    private static final class ProjectionStoreUnavailableException extends RuntimeException {

        private ProjectionStoreUnavailableException(RuntimeException cause) {
            super(cause);
        }
    }

    private void logOmission(EngineIntelligenceProjectionResult result) {
        result.omissionReason().ifPresent(reason -> log.atWarn()
                .addKeyValue("reason", reason)
                .log("Engine intelligence internal projection omitted."));
    }
}
