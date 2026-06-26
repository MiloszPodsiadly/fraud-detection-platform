package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceProjectionReadUnavailableException;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class FraudFeedbackService {

    private static final int MAX_REASON_CODES = 10;
    private static final int MAX_REASON_CODE_LENGTH = 128;
    private static final int MAX_NOTES_LENGTH = 500;
    private static final Pattern REASON_CODE_PATTERN = Pattern.compile("[A-Z0-9_]+");
    private static final List<String> UNSAFE_TERMS = List.of(
            "token",
            "secret",
            "password",
            "raw" + "payload",
            "raw" + "mlrequest",
            "raw" + "mlresponse",
            "raw" + "featurevector",
            "raw" + "evidence",
            "stack" + "trace",
            "exception" + "message",
            "final" + "decision",
            "payment" + "decision",
            "payment" + "authorization",
            "approve" + "payment",
            "decline" + "payment",
            "block" + "transaction",
            "authorize" + "payment"
    );

    private final FraudFeedbackRepository repository;
    private final FraudFeedbackMapper mapper;
    private final TransactionMonitoringUseCase transactionMonitoringUseCase;
    private final EngineIntelligenceReadService engineIntelligenceReadService;
    private final EngineIntelligenceResponseMapper engineIntelligenceResponseMapper;
    private final CurrentAnalystUser currentAnalystUser;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public FraudFeedbackService(
            FraudFeedbackRepository repository,
            FraudFeedbackMapper mapper,
            TransactionMonitoringUseCase transactionMonitoringUseCase,
            EngineIntelligenceReadService engineIntelligenceReadService,
            EngineIntelligenceResponseMapper engineIntelligenceResponseMapper,
            CurrentAnalystUser currentAnalystUser,
            AuditService auditService
    ) {
        this(
                repository,
                mapper,
                transactionMonitoringUseCase,
                engineIntelligenceReadService,
                engineIntelligenceResponseMapper,
                currentAnalystUser,
                auditService,
                Clock.systemUTC()
        );
    }

    FraudFeedbackService(
            FraudFeedbackRepository repository,
            FraudFeedbackMapper mapper,
            TransactionMonitoringUseCase transactionMonitoringUseCase,
            EngineIntelligenceReadService engineIntelligenceReadService,
            EngineIntelligenceResponseMapper engineIntelligenceResponseMapper,
            CurrentAnalystUser currentAnalystUser,
            AuditService auditService,
            Clock clock
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.transactionMonitoringUseCase = transactionMonitoringUseCase;
        this.engineIntelligenceReadService = engineIntelligenceReadService;
        this.engineIntelligenceResponseMapper = engineIntelligenceResponseMapper;
        this.currentAnalystUser = currentAnalystUser;
        this.auditService = auditService;
        this.clock = clock;
    }

    public FraudFeedbackResponse create(String transactionId, CreateFraudFeedbackRequest request) {
        ValidatedFeedback validated = validate(request);
        ScoredTransaction transaction = transactionMonitoringUseCase.getScoredTransaction(transactionId);
        String boundedTransactionId = transaction.transactionId();
        if (repository.existsByTransactionId(boundedTransactionId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FRAUD_FEEDBACK_ALREADY_RECORDED");
        }
        String actor = currentAnalystUser.get()
                .map(principal -> principal.userId())
                .filter(userId -> !userId.isBlank())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "FRAUD_FEEDBACK_ACTOR_REQUIRED"));

        FraudFeedbackRecord record = new FraudFeedbackRecord();
        record.setFeedbackId("ffb-" + UUID.randomUUID());
        record.setTransactionId(transaction.transactionId());
        record.setCustomerId(transaction.customerId());
        record.setCorrelationId(transaction.correlationId());
        record.setAnalystDecision(validated.analystDecision());
        record.setFeedbackLabel(validated.feedbackLabel());
        record.setLabelSource(FeedbackLabelSource.ANALYST_REVIEW);
        record.setFeedbackStatus(FraudFeedbackStatus.RECORDED);
        record.setCreatedAt(clock.instant());
        record.setCreatedBy(actor);
        record.setDecisionReasonCodes(validated.decisionReasonCodes());
        record.setNotes(validated.notes());
        record.setFraudScore(transaction.fraudScore());
        record.setRiskLevel(transaction.riskLevel());
        record.setAlertRecommended(transaction.alertRecommended());
        record.setScoredAt(transaction.scoredAt());
        record.setTransactionTimestamp(transaction.transactionTimestamp());
        snapshotEngineIntelligence(record, transaction.transactionId());
        snapshotAnalystRecommendation(record, transaction.analystRecommendation());

        FraudFeedbackRecord saved;
        try {
            saved = repository.save(record);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FRAUD_FEEDBACK_ALREADY_RECORDED", exception);
        }
        auditWrite(saved);
        return mapper.toResponse(saved);
    }

    public FraudFeedbackResponse get(String transactionId) {
        ScoredTransaction transaction = transactionMonitoringUseCase.getScoredTransaction(transactionId);
        String boundedTransactionId = transaction.transactionId();
        return repository.findByTransactionId(boundedTransactionId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FRAUD_FEEDBACK_NOT_FOUND"));
    }

    private void snapshotEngineIntelligence(FraudFeedbackRecord record, String transactionId) {
        try {
            EngineIntelligenceReadModel readModel = engineIntelligenceReadService.read(transactionId);
            var response = engineIntelligenceResponseMapper.toResponse(readModel);
            record.setEngineIntelligenceStatus(response.status());
            if (readModel != null && readModel.comparison() != null) {
                record.setAgreementStatus(readModel.comparison().agreementStatus());
                record.setRiskMismatchStatus(readModel.comparison().riskMismatchStatus());
                record.setScoreDeltaBucket(readModel.comparison().scoreDeltaBucket());
            }
        } catch (EngineIntelligenceProjectionReadUnavailableException exception) {
            record.setEngineIntelligenceStatus(EngineIntelligenceResponseStatus.UNAVAILABLE);
        }
    }

    private void snapshotAnalystRecommendation(FraudFeedbackRecord record, AnalystRecommendationResult recommendation) {
        if (recommendation == null) {
            recommendation = AnalystRecommendationResult.absent();
        }
        record.setAnalystRecommendationStatus(recommendation.status());
        record.setAnalystRecommendation(recommendation.recommendation());
        record.setAnalystRecommendationVersion(recommendation.recommendationVersion());
        record.setAnalystRecommendationGeneratedAt(recommendation.generatedAt());
        record.setAnalystRecommendationReasonCodes(recommendation.reasonCodes());
    }

    private void auditWrite(FraudFeedbackRecord saved) {
        auditService.audit(
                AuditAction.RECORD_FRAUD_FEEDBACK,
                AuditResourceType.FRAUD_FEEDBACK,
                saved.getFeedbackId(),
                saved.getCorrelationId(),
                saved.getCreatedBy(),
                AuditOutcome.SUCCESS,
                null,
                new AuditEventMetadataSummary(
                        saved.getCorrelationId(),
                        null,
                        "alert-service",
                        "fraud-feedback-v1",
                        null,
                        null,
                        "POST /api/v1/transactions/scored/{transactionId}/feedback",
                        "transactionId=" + saved.getTransactionId()
                                + ";feedbackLabel=" + saved.getFeedbackLabel()
                                + ";status=" + saved.getFeedbackStatus(),
                        1
                )
        );
    }

    private ValidatedFeedback validate(CreateFraudFeedbackRequest request) {
        if (request == null) {
            throw badRequest("FRAUD_FEEDBACK_REQUEST_REQUIRED");
        }
        if (request.analystDecision() == null) {
            throw badRequest("FRAUD_FEEDBACK_ANALYST_DECISION_REQUIRED");
        }
        if (request.feedbackLabel() == null) {
            throw badRequest("FRAUD_FEEDBACK_LABEL_REQUIRED");
        }
        validateDecisionMatchesLabel(request.analystDecision(), request.feedbackLabel());
        List<String> reasonCodes = validateReasonCodes(request.decisionReasonCodes());
        String notes = validateNotes(request.notes());
        return new ValidatedFeedback(request.analystDecision(), request.feedbackLabel(), reasonCodes, notes);
    }

    private void validateDecisionMatchesLabel(AnalystDecision decision, FraudFeedbackLabel label) {
        boolean matches = switch (decision) {
            case MARKED_FRAUD -> label == FraudFeedbackLabel.CONFIRMED_FRAUD;
            case MARKED_LEGITIMATE -> label == FraudFeedbackLabel.CONFIRMED_LEGITIMATE;
            case MARKED_INCONCLUSIVE -> label == FraudFeedbackLabel.INCONCLUSIVE;
            case REQUESTED_MORE_INFO -> label == FraudFeedbackLabel.NEEDS_MORE_INFO;
        };
        if (!matches) {
            throw badRequest("FRAUD_FEEDBACK_DECISION_LABEL_MISMATCH");
        }
    }

    private List<String> validateReasonCodes(List<String> reasonCodes) {
        if (reasonCodes == null) {
            return List.of();
        }
        if (reasonCodes.size() > MAX_REASON_CODES) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODES_TOO_MANY");
        }
        return reasonCodes.stream()
                .map(this::validateReasonCode)
                .toList();
    }

    private String validateReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODE_REQUIRED");
        }
        String normalized = reasonCode.trim();
        if (normalized.length() > MAX_REASON_CODE_LENGTH || !REASON_CODE_PATTERN.matcher(normalized).matches()) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODE_INVALID");
        }
        rejectUnsafeTerms(normalized, "FRAUD_FEEDBACK_REASON_CODE_UNSAFE");
        try {
            FraudFeedbackReasonCode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODE_UNKNOWN");
        }
        return normalized;
    }

    private String validateNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        String normalized = notes.trim();
        if (normalized.length() > MAX_NOTES_LENGTH) {
            throw badRequest("FRAUD_FEEDBACK_NOTES_TOO_LONG");
        }
        rejectUnsafeTerms(normalized, "FRAUD_FEEDBACK_NOTES_UNSAFE");
        return normalized;
    }

    private void rejectUnsafeTerms(String value, String reason) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        for (String term : UNSAFE_TERMS) {
            if (normalized.contains(term)) {
                throw badRequest(reason);
            }
        }
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record ValidatedFeedback(
            AnalystDecision analystDecision,
            FraudFeedbackLabel feedbackLabel,
            List<String> decisionReasonCodes,
            String notes
    ) {
    }
}
