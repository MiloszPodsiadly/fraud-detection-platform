package com.frauddetection.alert.engineintelligence.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceDiagnosticSignalProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceEngineProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class EngineIntelligenceFeedbackDatasetExportService {

    static final int MAX_RECORDS = 500;
    static final Duration MAX_DATE_RANGE = Duration.ofDays(31);

    private static final Comparator<EngineIntelligenceFeedbackDocument> FEEDBACK_EXPORT_ORDER =
            Comparator.comparing(EngineIntelligenceFeedbackDocument::getSubmittedAt, Comparator.reverseOrder())
                    .thenComparing(EngineIntelligenceFeedbackDocument::getFeedbackId);
    private static final Sort FEEDBACK_EXPORT_SORT = Sort.by(
            Sort.Order.desc("submittedAt"),
            Sort.Order.asc("feedbackId")
    );
    private static final List<String> FORBIDDEN_OUTPUT_TERMS = List.of(
            "customerId",
            "accountId",
            "cardId",
            "deviceId",
            "merchantId",
            "pan",
            "iban",
            "email",
            "phone",
            "submittedBy",
            "correlationId",
            "idempotencyKeyHash",
            "requestPayloadHash",
            "rawPayload",
            "rawRequest",
            "rawResponse",
            "rawEvidence",
            "rawContribution",
            "featureVector",
            "stackTrace",
            "exceptionMessage",
            "token",
            "secret",
            "endpoint",
            "metadata",
            "groundTruth",
            "modelTrainingLabel"
    );

    private final EngineIntelligenceFeedbackRepository feedbackRepository;
    private final EngineIntelligenceProjectionRepository projectionRepository;
    private final AlertRepository alertRepository;
    private final ObjectMapper objectMapper;

    public EngineIntelligenceFeedbackDatasetExportService(
            EngineIntelligenceFeedbackRepository feedbackRepository,
            EngineIntelligenceProjectionRepository projectionRepository,
            AlertRepository alertRepository,
            ObjectMapper objectMapper
    ) {
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "feedbackRepository is required");
        this.projectionRepository = Objects.requireNonNull(projectionRepository, "projectionRepository is required");
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required").copy().findAndRegisterModules();
    }

    public List<EngineIntelligenceFeedbackDatasetRecord> export(
            Instant fromInclusive,
            Instant toInclusive,
            int maxRecords
    ) {
        validateQuery(fromInclusive, toInclusive, maxRecords);
        List<EngineIntelligenceFeedbackDocument> feedback = deduplicateByTransaction(latestFeedback(
                fromInclusive,
                toInclusive,
                maxRecords
        )).stream()
                .limit(maxRecords)
                .toList();
        if (feedback.isEmpty()) {
            return List.of();
        }

        Set<String> transactionIds = feedback.stream()
                .map(EngineIntelligenceFeedbackDocument::getTransactionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, EngineIntelligenceProjection> projections = projections(transactionIds);

        return feedback.stream()
                .map(document -> record(document, projections.get(document.getTransactionId())))
                .toList();
    }

    public String exportJsonl(Instant fromInclusive, Instant toInclusive, int maxRecords) {
        return export(fromInclusive, toInclusive, maxRecords).stream()
                .map(this::json)
                .collect(Collectors.joining("\n"));
    }

    private void validateQuery(Instant fromInclusive, Instant toInclusive, int maxRecords) {
        if (fromInclusive == null) {
            throw new EngineIntelligenceFeedbackDatasetExportQueryException("fromInclusive is required");
        }
        if (toInclusive == null) {
            throw new EngineIntelligenceFeedbackDatasetExportQueryException("toInclusive is required");
        }
        if (fromInclusive.isAfter(toInclusive)) {
            throw new EngineIntelligenceFeedbackDatasetExportQueryException("fromInclusive must be before or equal to toInclusive");
        }
        if (Duration.between(fromInclusive, toInclusive).compareTo(MAX_DATE_RANGE) > 0) {
            throw new EngineIntelligenceFeedbackDatasetExportQueryException("date range must be less than or equal to 31 days");
        }
        if (maxRecords < 1 || maxRecords > MAX_RECORDS) {
            throw new EngineIntelligenceFeedbackDatasetExportQueryException("maxRecords must be between 1 and 500");
        }
    }

    private List<EngineIntelligenceFeedbackDocument> latestFeedback(
            Instant fromInclusive,
            Instant toInclusive,
            int maxRecords
    ) {
        try {
            return feedbackRepository.findByCreatedAtBetween(
                    fromInclusive,
                    toInclusive,
                    PageRequest.of(0, maxRecords, FEEDBACK_EXPORT_SORT)
            ).stream()
                    .map(this::validatedFeedback)
                    .sorted(FEEDBACK_EXPORT_ORDER)
                    .toList();
        } catch (EngineIntelligenceFeedbackDatasetExportUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackDatasetExportUnavailableException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.FEEDBACK_STORE_UNAVAILABLE
            );
        }
    }

    private List<EngineIntelligenceFeedbackDocument> deduplicateByTransaction(
            List<EngineIntelligenceFeedbackDocument> feedback
    ) {
        Map<String, EngineIntelligenceFeedbackDocument> latestByTransaction = new LinkedHashMap<>();
        for (EngineIntelligenceFeedbackDocument document : feedback) {
            latestByTransaction.putIfAbsent(document.getTransactionId(), document);
        }
        return List.copyOf(latestByTransaction.values());
    }

    private Map<String, EngineIntelligenceProjection> projections(Set<String> transactionIds) {
        try {
            return StreamSupport.stream(projectionRepository.findAllById(transactionIds).spliterator(), false)
                    .collect(Collectors.toMap(
                            EngineIntelligenceProjection::getTransactionId,
                            projection -> projection,
                            (first, ignored) -> first,
                            LinkedHashMap::new
                    ));
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackDatasetExportUnavailableException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.PROJECTION_STORE_UNAVAILABLE
            );
        }
    }

    private EngineIntelligenceFeedbackDatasetRecord record(
            EngineIntelligenceFeedbackDocument feedback,
            EngineIntelligenceProjection projection
    ) {
        Optional<AlertDocument> alert = alert(feedback.getTransactionId());
        AnalystDecision analystDecision = alert.map(AlertDocument::getAnalystDecision).orElse(null);
        Optional<EngineIntelligenceEngineProjection> rules = engine(projection, FraudEngineType.RULES);
        Optional<EngineIntelligenceEngineProjection> ml = engine(projection, FraudEngineType.ML_MODEL);

        return new EngineIntelligenceFeedbackDatasetRecord(
                safeString(feedback.getTransactionId()),
                label(analystDecision),
                analystDecision,
                labelSource(analystDecision),
                Objects.requireNonNull(feedback.getFeedbackType(), "feedback type is required"),
                Objects.requireNonNull(feedback.getUsefulness(), "usefulness is required"),
                Objects.requireNonNull(feedback.getAccuracyAssessment(), "accuracy assessment is required"),
                rules.map(EngineIntelligenceEngineProjection::riskLevel).orElse(null),
                ml.map(EngineIntelligenceEngineProjection::riskLevel).orElse(null),
                rules.map(EngineIntelligenceEngineProjection::scoreBucket).orElse(null),
                ml.map(EngineIntelligenceEngineProjection::scoreBucket).orElse(null),
                projection == null ? null : projection.getComparisonStatus(),
                projection == null ? null : projection.getRiskMismatchStatus(),
                reasonCodes(projection),
                diagnosticSignals(projection),
                projection == null ? null : projection.getContractVersion(),
                projection == null ? null : projection.getGeneratedAt(),
                feedback.getCreatedAt() == null ? feedback.getSubmittedAt() : feedback.getCreatedAt(),
                alert.map(AlertDocument::getDecidedAt).orElse(null),
                projection == null
                        ? EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING
                        : EngineIntelligenceFeedbackDatasetProjectionStatus.PRESENT
        );
    }

    private Optional<AlertDocument> alert(String transactionId) {
        try {
            Optional<AlertDocument> alert = alertRepository.findByTransactionId(transactionId);
            return alert == null ? Optional.empty() : alert;
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackDatasetExportUnavailableException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.ALERT_STORE_UNAVAILABLE
            );
        }
    }

    private Optional<EngineIntelligenceEngineProjection> engine(
            EngineIntelligenceProjection projection,
            FraudEngineType engineType
    ) {
        if (projection == null) {
            return Optional.empty();
        }
        return projection.getEngines().stream()
                .filter(engine -> engine.engineType() == engineType)
                .sorted(Comparator.comparing(EngineIntelligenceEngineProjection::engineId))
                .findFirst();
    }

    private EngineIntelligenceFeedbackDatasetLabel label(AnalystDecision analystDecision) {
        if (analystDecision == AnalystDecision.CONFIRMED_FRAUD) {
            return EngineIntelligenceFeedbackDatasetLabel.POSITIVE;
        }
        if (analystDecision == AnalystDecision.MARKED_LEGITIMATE) {
            return EngineIntelligenceFeedbackDatasetLabel.NEGATIVE;
        }
        return EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING;
    }

    private EngineIntelligenceFeedbackDatasetLabelSource labelSource(AnalystDecision analystDecision) {
        return analystDecision == null
                ? EngineIntelligenceFeedbackDatasetLabelSource.NO_ANALYST_DECISION
                : EngineIntelligenceFeedbackDatasetLabelSource.ANALYST_DECISION;
    }

    private List<String> reasonCodes(EngineIntelligenceProjection projection) {
        if (projection == null) {
            return List.of();
        }
        return projection.getEngines().stream()
                .flatMap(engine -> engine.reasonCodes().stream())
                .map(this::safeString)
                .distinct()
                .sorted()
                .toList();
    }

    private List<EngineIntelligenceFeedbackDatasetDiagnosticSignal> diagnosticSignals(
            EngineIntelligenceProjection projection
    ) {
        if (projection == null) {
            return List.of();
        }
        return projection.getDiagnosticSignals().stream()
                .sorted(Comparator
                        .comparing(EngineIntelligenceDiagnosticSignalProjection::engineType)
                        .thenComparing(EngineIntelligenceDiagnosticSignalProjection::signalCategory)
                        .thenComparing(EngineIntelligenceDiagnosticSignalProjection::reasonCode))
                .map(signal -> new EngineIntelligenceFeedbackDatasetDiagnosticSignal(
                        signal.engineType(),
                        signal.signalCategory(),
                        signal.riskLevel(),
                        signal.scoreBucket(),
                        safeString(signal.reasonCode())
                ))
                .toList();
    }

    private EngineIntelligenceFeedbackDocument validatedFeedback(EngineIntelligenceFeedbackDocument document) {
        if (document == null
                || !StringUtils.hasText(document.getTransactionId())
                || !StringUtils.hasText(document.getFeedbackId())
                || document.getSubmittedAt() == null
                || document.getFeedbackType() == null
                || document.getUsefulness() == null
                || document.getAccuracyAssessment() == null) {
            throw new EngineIntelligenceFeedbackDatasetExportUnavailableException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_SOURCE_DATA
            );
        }
        return document;
    }

    private String safeString(String value) {
        if (!StringUtils.hasText(value) || containsForbiddenOutputTerm(value)) {
            throw new EngineIntelligenceFeedbackDatasetExportUnavailableException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_SOURCE_DATA
            );
        }
        return value;
    }

    private boolean containsForbiddenOutputTerm(String value) {
        String compact = compact(value);
        return FORBIDDEN_OUTPUT_TERMS.stream()
                .map(this::compact)
                .anyMatch(compact::contains);
    }

    private String compact(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String json(EngineIntelligenceFeedbackDatasetRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException exception) {
            throw new EngineIntelligenceFeedbackDatasetExportUnavailableException(
                    EngineIntelligenceFeedbackDatasetExportFailureReason.SERIALIZATION_FAILURE
            );
        }
    }
}
