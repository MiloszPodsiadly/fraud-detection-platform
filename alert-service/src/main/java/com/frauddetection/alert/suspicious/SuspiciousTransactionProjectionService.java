package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public class SuspiciousTransactionProjectionService {

    private static final Logger log = LoggerFactory.getLogger(SuspiciousTransactionProjectionService.class);

    private final SuspiciousTransactionRepository repository;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public SuspiciousTransactionProjectionService(
            SuspiciousTransactionRepository repository,
            AlertServiceMetrics metrics
    ) {
        this(repository, metrics, Clock.systemUTC());
    }

    SuspiciousTransactionProjectionService(
            SuspiciousTransactionRepository repository,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Optional<SuspiciousTransactionDocument> projectOrUpdate(TransactionScoredEvent event, String linkedAlertId) {
        if (!isAlertWorthy(event)) {
            metrics.recordSuspiciousTransactionProjectionSkipped("non_alert_worthy");
            return Optional.empty();
        }
        if (!hasText(event.transactionId()) || !hasText(event.eventId())) {
            metrics.recordSuspiciousTransactionProjectionSkipped("missing_required_lineage");
            log.atWarn()
                    .addKeyValue("reason", "missing_required_lineage")
                    .addKeyValue("hasTransactionId", hasText(event.transactionId()))
                    .addKeyValue("hasSourceEventId", hasText(event.eventId()))
                    .log("Skipped suspicious transaction read-model projection.");
            return Optional.empty();
        }

        try {
            Instant now = clock.instant();
            Optional<SuspiciousTransactionDocument> existing =
                    repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId());
            SuspiciousTransactionDocument document = existing.orElseGet(() -> newDocument(event, now));
            applyEvent(document, event, linkedAlertId, now);
            SuspiciousTransactionDocument saved = repository.save(document);
            metrics.recordSuspiciousTransactionProjection(
                    existing.isPresent() ? "updated" : "created",
                    saved.getStatus()
            );
            return Optional.of(saved);
        } catch (DuplicateKeyException exception) {
            return readBackAfterDuplicateKey(event, linkedAlertId);
        } catch (RuntimeException exception) {
            metrics.recordSuspiciousTransactionProjectionError("projection_error");
            log.atWarn()
                    .addKeyValue("reason", "projection_error")
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("Suspicious transaction read-model projection failed.");
            return Optional.empty();
        }
    }

    private Optional<SuspiciousTransactionDocument> readBackAfterDuplicateKey(
            TransactionScoredEvent event,
            String linkedAlertId
    ) {
        try {
            Optional<SuspiciousTransactionDocument> existing =
                    repository.findByTransactionIdAndSourceEventId(event.transactionId(), event.eventId());
            if (existing.isEmpty()) {
                metrics.recordSuspiciousTransactionProjectionError("duplicate_readback_missing");
                log.atWarn()
                        .addKeyValue("reason", "duplicate_readback_missing")
                        .log("Suspicious transaction duplicate insert race could not be resolved by readback.");
                return Optional.empty();
            }

            SuspiciousTransactionDocument document = existing.get();
            if (hasText(linkedAlertId) && !hasText(document.getLinkedAlertId())) {
                document.setLinkedAlertId(linkedAlertId);
                document.setStatus(SuspiciousTransactionStatus.ALERT_CREATED);
                document.setUpdatedAt(clock.instant());
                SuspiciousTransactionDocument saved = repository.save(document);
                metrics.recordSuspiciousTransactionProjection("duplicate_retry", saved.getStatus());
                logDuplicateReadbackResolved();
                return Optional.of(saved);
            }

            metrics.recordSuspiciousTransactionProjection("duplicate_retry", document.getStatus());
            logDuplicateReadbackResolved();
            return existing;
        } catch (RuntimeException readbackException) {
            metrics.recordSuspiciousTransactionProjectionError("duplicate_readback_failed");
            log.atWarn()
                    .addKeyValue("reason", "duplicate_readback_failed")
                    .addKeyValue("exceptionType", readbackException.getClass().getSimpleName())
                    .log("Suspicious transaction duplicate readback failed.");
            return Optional.empty();
        }
    }

    private void logDuplicateReadbackResolved() {
        log.atInfo()
                .addKeyValue("reason", "duplicate_retry")
                .log("Suspicious transaction duplicate insert race resolved by readback.");
    }

    private SuspiciousTransactionDocument newDocument(TransactionScoredEvent event, Instant now) {
        SuspiciousTransactionDocument document = new SuspiciousTransactionDocument();
        document.setSuspiciousTransactionId(deterministicId(event.transactionId(), event.eventId()));
        document.setTransactionId(event.transactionId());
        document.setSourceEventId(event.eventId());
        document.setCreatedAt(now);
        return document;
    }

    private void applyEvent(
            SuspiciousTransactionDocument document,
            TransactionScoredEvent event,
            String linkedAlertId,
            Instant now
    ) {
        document.setCorrelationId(event.correlationId());
        document.setCustomerId(event.customerId());
        document.setAccountId(event.accountId());
        document.setRiskScore(event.fraudScore());
        document.setRiskLevel(event.riskLevel());
        document.setDetectionSource(detectionSource(event));
        document.setReasonCodes(event.reasonCodes());
        EvidenceStatus evidenceStatus = evidenceStatus(event);
        document.setEvidenceStatus(evidenceStatus);
        document.setEvidenceSnapshotItemCount(safeCount(event.scoringEvidence()));
        document.setEvidenceProjectionState(evidenceProjectionState(evidenceStatus));
        if (!hasText(event.correlationId())) {
            log.atWarn()
                    .addKeyValue("reason", "missing_correlation_id")
                    .log("Suspicious transaction read-model projection has partial lineage metadata.");
        }
        document.setLinkedAlertId(hasText(linkedAlertId) ? linkedAlertId : document.getLinkedAlertId());
        document.setStatus(hasText(document.getLinkedAlertId()) ? SuspiciousTransactionStatus.ALERT_CREATED : SuspiciousTransactionStatus.NEW);
        document.setDetectedAt(event.inferenceTimestamp() == null ? now : event.inferenceTimestamp());
        document.setUpdatedAt(now);
        document.setScoreDecisionId(scoreDecisionId(event));
        document.setScoringStrategy(event.scoringStrategy());
        document.setModelName(event.modelName());
        document.setModelVersion(event.modelVersion());
    }

    private boolean isAlertWorthy(TransactionScoredEvent event) {
        return event != null && (Boolean.TRUE.equals(event.alertRecommended()) || isHighOrCritical(event.riskLevel()));
    }

    private boolean isHighOrCritical(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    private EvidenceStatus evidenceStatus(TransactionScoredEvent event) {
        EvidenceStatus status = evidenceStatus(event.scoringEvidence());
        if (!hasText(event.correlationId()) && status == EvidenceStatus.AVAILABLE) {
            return EvidenceStatus.PARTIAL;
        }
        return status;
    }

    EvidenceStatus evidenceStatus(List<ScoringEvidenceItem> scoringEvidence) {
        if (scoringEvidence == null || scoringEvidence.isEmpty()) {
            return EvidenceStatus.PARTIAL;
        }
        boolean hasAvailable = false;
        boolean hasError = false;
        boolean hasPartialOrLegacy = false;
        boolean hasUnavailableOrNotApplicable = false;
        for (ScoringEvidenceItem item : scoringEvidence) {
            if (item == null || item.status() == null) {
                hasError = true;
                continue;
            }
            switch (item.status()) {
                case AVAILABLE -> hasAvailable = true;
                case ERROR -> hasError = true;
                case PARTIAL, LEGACY -> hasPartialOrLegacy = true;
                case UNAVAILABLE, NOT_APPLICABLE -> hasUnavailableOrNotApplicable = true;
            }
        }
        if (hasError) {
            return EvidenceStatus.ERROR;
        }
        if (hasPartialOrLegacy) {
            return EvidenceStatus.PARTIAL;
        }
        if (hasAvailable && hasUnavailableOrNotApplicable) {
            return EvidenceStatus.PARTIAL;
        }
        if (hasUnavailableOrNotApplicable) {
            return EvidenceStatus.UNAVAILABLE;
        }
        if (hasAvailable) {
            return EvidenceStatus.AVAILABLE;
        }
        return EvidenceStatus.UNAVAILABLE;
    }

    String evidenceProjectionState(EvidenceStatus status) {
        return switch (status) {
            case AVAILABLE -> "AVAILABLE_METADATA";
            case ERROR -> "ERROR_METADATA";
            case PARTIAL, LEGACY -> "PARTIAL_METADATA";
            case UNAVAILABLE, NOT_APPLICABLE, STALE -> "UNAVAILABLE_METADATA";
        };
    }

    private DetectionSource detectionSource(TransactionScoredEvent event) {
        if (hasFallbackEvidence(event.scoringEvidence())) {
            return DetectionSource.SCORING_FALLBACK;
        }
        boolean hasRuleEvidence = hasEvidenceSource(event.scoringEvidence(), ScoringEvidenceSource.RULE_BASED_SCORING);
        boolean hasMlEvidence = hasEvidenceSource(event.scoringEvidence(), ScoringEvidenceSource.ML_MODEL)
                || hasEvidenceSource(event.scoringEvidence(), ScoringEvidenceSource.ML_RUNTIME);
        if (hasRuleEvidence && hasMlEvidence) {
            return DetectionSource.HYBRID_SCORING;
        }
        if (hasMlEvidence || contains(event.scoringStrategy(), "ML")) {
            return DetectionSource.ML_MODEL;
        }
        if (hasEvidenceSource(event.scoringEvidence(), ScoringEvidenceSource.LEGACY_SCORING)) {
            return DetectionSource.LEGACY_SCORING;
        }
        return DetectionSource.RULE_ENGINE;
    }

    private boolean hasFallbackEvidence(List<ScoringEvidenceItem> scoringEvidence) {
        return hasEvidenceSource(scoringEvidence, ScoringEvidenceSource.SCORING_FALLBACK);
    }

    private boolean hasEvidenceSource(List<ScoringEvidenceItem> scoringEvidence, ScoringEvidenceSource source) {
        if (scoringEvidence == null) {
            return false;
        }
        return scoringEvidence.stream().filter(Objects::nonNull).anyMatch(item -> item.source() == source);
    }

    private int safeCount(List<ScoringEvidenceItem> scoringEvidence) {
        return scoringEvidence == null ? 0 : scoringEvidence.size();
    }

    private String scoreDecisionId(TransactionScoredEvent event) {
        if (event.scoreDetails() == null) {
            return null;
        }
        Object value = event.scoreDetails().get("scoreDecisionId");
        return value instanceof String scoreDecisionId && hasText(scoreDecisionId) ? scoreDecisionId : null;
    }

    private boolean contains(String value, String token) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(token);
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private String deterministicId(String transactionId, String sourceEventId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((transactionId + "|" + sourceEventId).getBytes(StandardCharsets.UTF_8));
            return "suspicious-transaction-" + HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for suspicious transaction idempotency", exception);
        }
    }
}
