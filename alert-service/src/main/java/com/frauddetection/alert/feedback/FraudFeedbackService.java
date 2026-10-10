package com.frauddetection.alert.feedback;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxService;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.feedback.assembly.FraudFeedbackRecordAssembler;
import com.frauddetection.alert.feedback.snapshot.EngineIntelligenceFeedbackSnapshotter;
import com.frauddetection.alert.feedback.snapshot.MlPredictionEvidenceSnapshotter;
import com.frauddetection.alert.feedback.validation.FraudFeedbackRequestValidator;
import com.frauddetection.alert.feedback.validation.ValidatedFraudFeedback;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

@Service
public class FraudFeedbackService {

    private final FraudFeedbackRepository repository;
    private final FraudFeedbackMapper mapper;
    private final TransactionMonitoringUseCase transactionMonitoringUseCase;
    private final CurrentAnalystUser currentAnalystUser;
    private final WriteActionAuditOutboxService auditOutboxService;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final FraudFeedbackRequestValidator requestValidator;
    private final FraudFeedbackRecordAssembler recordAssembler;
    private final EngineIntelligenceFeedbackSnapshotter engineIntelligenceSnapshotter;
    private final MlPredictionEvidenceSnapshotter mlPredictionEvidenceSnapshotter;
    private final Clock clock;

    @Autowired
    public FraudFeedbackService(
            FraudFeedbackRepository repository,
            FraudFeedbackMapper mapper,
            TransactionMonitoringUseCase transactionMonitoringUseCase,
            CurrentAnalystUser currentAnalystUser,
            WriteActionAuditOutboxService auditOutboxService,
            RegulatedMutationTransactionRunner transactionRunner,
            FraudFeedbackRequestValidator requestValidator,
            FraudFeedbackRecordAssembler recordAssembler,
            EngineIntelligenceFeedbackSnapshotter engineIntelligenceSnapshotter,
            MlPredictionEvidenceSnapshotter mlPredictionEvidenceSnapshotter
    ) {
        this(
                repository,
                mapper,
                transactionMonitoringUseCase,
                currentAnalystUser,
                auditOutboxService,
                transactionRunner,
                requestValidator,
                recordAssembler,
                engineIntelligenceSnapshotter,
                mlPredictionEvidenceSnapshotter,
                Clock.systemUTC()
        );
    }

    FraudFeedbackService(
            FraudFeedbackRepository repository,
            FraudFeedbackMapper mapper,
            TransactionMonitoringUseCase transactionMonitoringUseCase,
            CurrentAnalystUser currentAnalystUser,
            WriteActionAuditOutboxService auditOutboxService,
            RegulatedMutationTransactionRunner transactionRunner,
            FraudFeedbackRequestValidator requestValidator,
            FraudFeedbackRecordAssembler recordAssembler,
            EngineIntelligenceFeedbackSnapshotter engineIntelligenceSnapshotter,
            MlPredictionEvidenceSnapshotter mlPredictionEvidenceSnapshotter,
            Clock clock
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.transactionMonitoringUseCase = transactionMonitoringUseCase;
        this.currentAnalystUser = currentAnalystUser;
        this.auditOutboxService = auditOutboxService;
        this.transactionRunner = transactionRunner;
        this.requestValidator = requestValidator;
        this.recordAssembler = recordAssembler;
        this.engineIntelligenceSnapshotter = engineIntelligenceSnapshotter;
        this.mlPredictionEvidenceSnapshotter = mlPredictionEvidenceSnapshotter;
        this.clock = clock;
    }

    public FraudFeedbackResponse create(String transactionId, CreateFraudFeedbackRequest request) {
        ValidatedFraudFeedback validated = requestValidator.validate(request);
        String actor = currentAnalystUser.get()
                .map(principal -> principal.userId())
                .filter(userId -> !userId.isBlank())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "FRAUD_FEEDBACK_ACTOR_REQUIRED"));
        try {
            FraudFeedbackRecord saved = transactionRunner.runLocalCommit(
                    () -> createWithAuthoritativeSnapshot(transactionId, validated, actor)
            );
            return mapper.toResponse(saved);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FRAUD_FEEDBACK_ALREADY_RECORDED", exception);
        }
    }

    private FraudFeedbackRecord createWithAuthoritativeSnapshot(
            String transactionId,
            ValidatedFraudFeedback validated,
            String actor
    ) {
        ScoredTransaction transaction = transactionMonitoringUseCase.getScoredTransaction(transactionId);
        ScoringOccurrenceOwnership ownership = requireAuthoritativeOccurrence(transaction);
        String boundedTransactionId = transaction.transactionId();
        if (repository.existsByTransactionId(boundedTransactionId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FRAUD_FEEDBACK_ALREADY_RECORDED");
        }

        FraudFeedbackRecord record = new FraudFeedbackRecord();
        record.captureScoringOccurrence(ownership);
        recordAssembler.assemble(
                record,
                transaction,
                validated,
                actor,
                clock.instant()
        );
        engineIntelligenceSnapshotter.snapshot(record, transaction);
        mlPredictionEvidenceSnapshotter.snapshot(record, transaction, ownership);
        return persistFeedbackWithAuditIntent(record);
    }

    private ScoringOccurrenceOwnership requireAuthoritativeOccurrence(ScoredTransaction transaction) {
        ScoringOccurrenceOwnership ownership = transaction == null
                ? null
                : transaction.scoringOccurrenceOwnership();
        if (ownership == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "FRAUD_FEEDBACK_SCORING_OCCURRENCE_UNAVAILABLE"
            );
        }
        return ownership;
    }

    public FraudFeedbackResponse get(String transactionId) {
        ScoredTransaction transaction = transactionMonitoringUseCase.getScoredTransaction(transactionId);
        String boundedTransactionId = transaction.transactionId();
        return repository.findByTransactionId(boundedTransactionId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FRAUD_FEEDBACK_NOT_FOUND"));
    }

    private void persistAuditIntent(FraudFeedbackRecord saved) {
        auditOutboxService.createPendingAudit(
                "RECORD_FRAUD_FEEDBACK:FRAUD_FEEDBACK:" + saved.getFeedbackId(),
                AuditAction.RECORD_FRAUD_FEEDBACK,
                AuditResourceType.FRAUD_FEEDBACK,
                saved.getFeedbackId(),
                saved.getCorrelationId(),
                saved.getCreatedBy(),
                AuditOutcome.SUCCESS,
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

    private FraudFeedbackRecord persistFeedbackWithAuditIntent(FraudFeedbackRecord record) {
        FraudFeedbackRecord saved = repository.save(record);
        try {
            persistAuditIntent(saved);
        } catch (RuntimeException outboxException) {
            rollbackSavedFeedback(saved, outboxException);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FRAUD_FEEDBACK_AUDIT_OUTBOX_UNAVAILABLE",
                    outboxException
            );
        }
        return saved;
    }

    private void rollbackSavedFeedback(FraudFeedbackRecord saved, RuntimeException auditException) {
        if (transactionRunner != null && transactionRunner.mode() != RegulatedMutationTransactionMode.OFF) {
            return;
        }
        try {
            repository.deleteById(saved.getFeedbackId());
        } catch (RuntimeException cleanupException) {
            auditException.addSuppressed(cleanupException);
        }
    }

}
