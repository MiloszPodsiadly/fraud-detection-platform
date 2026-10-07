package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
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

    private final EngineIntelligenceProjectionWriteFence writeFence;
    private final ScoredTransactionRepository scoredTransactionRepository;
    private final EngineIntelligenceProjectionMapper mapper;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public EngineIntelligenceProjectionService(
            EngineIntelligenceProjectionWriteFence writeFence,
            EngineIntelligenceProjectionMapper mapper,
            AlertServiceMetrics metrics,
            ScoredTransactionRepository scoredTransactionRepository
    ) {
        this(writeFence, mapper, metrics, scoredTransactionRepository, Clock.systemUTC());
    }

    EngineIntelligenceProjectionService(
            EngineIntelligenceProjectionWriteFence writeFence,
            EngineIntelligenceProjectionMapper mapper,
            AlertServiceMetrics metrics,
            ScoredTransactionRepository scoredTransactionRepository,
            Clock clock
    ) {
        this.writeFence = Objects.requireNonNull(writeFence, "writeFence is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.scoredTransactionRepository = Objects.requireNonNull(
                scoredTransactionRepository,
                "scoredTransactionRepository is required"
        );
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EngineIntelligenceProjectionResult project(TransactionScoredEvent event) {
        if (event == null) {
            return project(null, null, null, null, null, false, false);
        }
        return project(
                event.transactionId(),
                event.eventId(),
                event.createdAt(),
                event.engineIntelligence() == null ? null : ScoringOccurrenceFingerprint.from(event),
                event.engineIntelligence(),
                false,
                false
        );
    }

    @Transactional(transactionManager = "mongoTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public EngineIntelligenceProjectionResult projectCurrentOccurrence(TransactionScoredEvent event) {
        if (event == null) {
            return project(null, null, null, null, null, true, true);
        }
        String sourceEventFingerprint = event.engineIntelligence() == null
                ? null
                : ScoringOccurrenceFingerprint.from(event);
        return project(
                event.transactionId(),
                event.eventId(),
                event.createdAt(),
                sourceEventFingerprint,
                event.engineIntelligence(),
                true,
                true
        );
    }

    @Transactional(transactionManager = "mongoTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public EngineIntelligenceProjectionResult projectDeferredOccurrence(
            String transactionId,
            String sourceEventId,
            Instant sourceEventCreatedAt,
            String sourceEventFingerprint,
            EngineIntelligenceSummary engineIntelligence
    ) {
        return project(
                transactionId,
                sourceEventId,
                sourceEventCreatedAt,
                sourceEventFingerprint,
                engineIntelligence,
                true,
                true
        );
    }

    private EngineIntelligenceProjectionResult project(
            String transactionId,
            String sourceEventId,
            Instant sourceEventCreatedAt,
            String sourceEventFingerprint,
            EngineIntelligenceSummary engineIntelligence,
            boolean storeRequired,
            boolean requireCurrentOccurrence
    ) {
        Instant startedAt = clock.instant();
        metrics.recordEngineIntelligenceProjectionAttempt();
        try {
            if (transactionId == null || sourceEventId == null || sourceEventCreatedAt == null) {
                EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                        EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE
                );
                metrics.recordEngineIntelligenceProjectionOmitted(EngineIntelligenceProjectionMetricReason.INVALID_PROJECTION_SHAPE);
                logOmission(result);
                return result;
            }
            if (engineIntelligence == null) {
                EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                        EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_ABSENT
                );
                metrics.recordEngineIntelligenceProjectionOmitted(EngineIntelligenceProjectionMetricReason.ENGINE_INTELLIGENCE_ABSENT);
                if (requireCurrentOccurrence) {
                    metrics.recordDiagnosticProjectionDisposition(
                            EngineIntelligenceProjectionDisposition.OPTIONAL_DIAGNOSTICS_ABSENT
                    );
                }
                return result;
            }

            if (requireCurrentOccurrence && !isCurrentOccurrence(
                    transactionId,
                    sourceEventId,
                    sourceEventCreatedAt,
                    sourceEventFingerprint
            )) {
                EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                        EngineIntelligenceProjectionOmissionReason.SCORING_OCCURRENCE_NOT_CURRENT
                );
                metrics.recordEngineIntelligenceProjectionOmitted(
                        EngineIntelligenceProjectionMetricReason.STALE_OCCURRENCE
                );
                metrics.recordDiagnosticProjectionDisposition(
                        EngineIntelligenceProjectionDisposition.STALE_OCCURRENCE
                );
                return result;
            }

            ScoringOccurrenceOwnership ownership = new ScoringOccurrenceOwnership(
                    sourceEventId,
                    sourceEventCreatedAt,
                    sourceEventFingerprint
            );
            EngineIntelligenceProjectionResult result = mapper.map(
                    transactionId,
                    ownership,
                    engineIntelligence,
                    null
            );
            if (result.projection().isPresent()) {
                EngineIntelligenceProjectionWriteResult writeResult = saveFenced(
                        result.projection().orElseThrow()
                );
                if (writeResult.status() == EngineIntelligenceProjectionWriteResult.Status.STALE) {
                    EngineIntelligenceProjectionResult stale = EngineIntelligenceProjectionResult.omitted(
                            EngineIntelligenceProjectionOmissionReason.SCORING_OCCURRENCE_NOT_CURRENT
                    );
                    metrics.recordEngineIntelligenceProjectionOmitted(
                            EngineIntelligenceProjectionMetricReason.STALE_OCCURRENCE
                    );
                    if (requireCurrentOccurrence) {
                        metrics.recordDiagnosticProjectionDisposition(
                                EngineIntelligenceProjectionDisposition.STALE_OCCURRENCE
                        );
                    }
                    return stale;
                }
                if (writeResult.status()
                        == EngineIntelligenceProjectionWriteResult.Status.SOURCE_PAYLOAD_CONFLICT) {
                    throw new SourceOccurrencePayloadConflictException();
                }
                result = EngineIntelligenceProjectionResult.projected(writeResult.projection());
                metrics.recordEngineIntelligenceProjectionSuccess();
                if (requireCurrentOccurrence) {
                    metrics.recordDiagnosticProjectionDisposition(
                            EngineIntelligenceProjectionDisposition.AUTHORITATIVE_OCCURRENCE_READY
                    );
                }
            } else {
                metrics.recordEngineIntelligenceProjectionOmitted(metricReason(result.omissionReason().orElse(null)));
                if (requireCurrentOccurrence) {
                    metrics.recordDiagnosticProjectionDisposition(
                            EngineIntelligenceProjectionDisposition.PERMANENT_INVALID_DIAGNOSTICS
                    );
                }
            }
            logOmission(result);
            return result;
        } catch (EngineIntelligenceProjectionValidationException exception) {
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(exception.reason());
            metrics.recordEngineIntelligenceProjectionOmitted(metricReason(exception.reason()));
            logOmission(result);
            if (requireCurrentOccurrence) {
                metrics.recordDiagnosticProjectionDisposition(
                        EngineIntelligenceProjectionDisposition.PERMANENT_INVALID_DIAGNOSTICS
                );
            }
            return result;
        } catch (CurrentScoringOccurrencePendingException exception) {
            metrics.recordDiagnosticProjectionDisposition(
                    EngineIntelligenceProjectionDisposition.BASELINE_OCCURRENCE_PENDING
            );
            throw exception;
        } catch (SourceOccurrencePayloadConflictException exception) {
            metrics.recordDiagnosticProjectionDisposition(
                    EngineIntelligenceProjectionDisposition.SOURCE_OCCURRENCE_PAYLOAD_CONFLICT
            );
            throw exception;
        } catch (ProjectionStoreUnavailableException exception) {
            metrics.recordEngineIntelligenceProjectionFailure(EngineIntelligenceProjectionMetricReason.STORE_UNAVAILABLE);
            if (requireCurrentOccurrence) {
                metrics.recordDiagnosticProjectionDisposition(
                        EngineIntelligenceProjectionDisposition.TRANSIENT_STORAGE_UNAVAILABLE
                );
            }
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

    private EngineIntelligenceProjectionWriteResult saveFenced(EngineIntelligenceProjection projection) {
        try {
            return writeFence.write(projection);
        } catch (RuntimeException exception) {
            if (exception instanceof SourceOccurrencePayloadConflictException) {
                throw exception;
            }
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

    private boolean isCurrentOccurrence(
            String transactionId,
            String sourceEventId,
            Instant sourceEventCreatedAt,
            String sourceEventFingerprint
    ) {
        ScoredTransactionDocument current;
        try {
            current = scoredTransactionRepository.findById(transactionId).orElse(null);
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
        if (ownership.sourceEventId().equals(sourceEventId)) {
            if (!Objects.equals(current.getSourceEventFingerprint(), sourceEventFingerprint)) {
                throw new SourceOccurrencePayloadConflictException();
            }
            return true;
        }
        int timestampOrder = sourceEventCreatedAt.compareTo(ownership.sourceEventCreatedAt());
        int occurrenceOrder = timestampOrder != 0
                ? timestampOrder
                : sourceEventId.compareTo(ownership.sourceEventId());
        if (occurrenceOrder > 0) {
            throw new CurrentScoringOccurrencePendingException();
        }
        return false;
    }

    public static final class ProjectionStoreUnavailableException extends RuntimeException {

        private ProjectionStoreUnavailableException(RuntimeException cause) {
            super("ENGINE_INTELLIGENCE_PROJECTION_STORE_UNAVAILABLE", cause);
        }
    }

    public static final class CurrentScoringOccurrencePendingException extends RuntimeException {

        public CurrentScoringOccurrencePendingException() {
            super("CURRENT_SCORING_OCCURRENCE_PENDING");
        }
    }

    public static final class SourceOccurrencePayloadConflictException extends RuntimeException {

        public SourceOccurrencePayloadConflictException() {
            super("SOURCE_OCCURRENCE_PAYLOAD_CONFLICT");
        }
    }

    private void logOmission(EngineIntelligenceProjectionResult result) {
        result.omissionReason().ifPresent(reason -> log.atWarn()
                .addKeyValue("reason", reason)
                .log("Engine intelligence internal projection omitted."));
    }
}
