package com.frauddetection.alert.service;

import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.AssignFraudCaseRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.FraudCaseAuditResponse;
import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.api.FraudCaseSummaryResponse;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.fraudcase.FraudCaseActorUnavailableException;
import com.frauddetection.alert.fraudcase.FraudCaseAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.fraudcase.FraudCaseSearchCriteria;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseTransitionPolicy;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseAuditEntryDocument;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDecisionDocument;
import com.frauddetection.alert.persistence.FraudCaseDecisionRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.FraudCaseTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationIntent;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FraudCaseManagementService {

    private static final String SUSPICION_TYPE = "RAPID_TRANSFER_BURST_20K_PLN";
    private static final BigDecimal DEFAULT_THRESHOLD_PLN = BigDecimal.valueOf(20_000);

    private final FraudCaseRepository fraudCaseRepository;
    private final ScoredTransactionRepository scoredTransactionRepository;
    private final AlertRepository alertRepository;
    private final FraudCaseNoteRepository noteRepository;
    private final FraudCaseDecisionRepository decisionRepository;
    private final FraudCaseAuditRepository auditRepository;
    private final FraudCaseSearchRepository searchRepository;
    private final AnalystActorResolver analystActorResolver;
    private final AlertServiceMetrics metrics;
    private final FraudCaseUpdateMutationHandler updateMutationHandler;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final FraudCaseTransitionPolicy transitionPolicy;
    private final FraudCaseAuditService caseAuditService;
    private final FraudCaseResponseMapper responseMapper;

    public FraudCaseManagementService(
            FraudCaseRepository fraudCaseRepository,
            ScoredTransactionRepository scoredTransactionRepository,
            AlertRepository alertRepository,
            FraudCaseNoteRepository noteRepository,
            FraudCaseDecisionRepository decisionRepository,
            FraudCaseAuditRepository auditRepository,
            FraudCaseSearchRepository searchRepository,
            AnalystActorResolver analystActorResolver,
            AlertServiceMetrics metrics,
            FraudCaseUpdateMutationHandler updateMutationHandler,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            RegulatedMutationTransactionRunner transactionRunner,
            FraudCaseTransitionPolicy transitionPolicy,
            FraudCaseAuditService caseAuditService,
            FraudCaseResponseMapper responseMapper
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.scoredTransactionRepository = scoredTransactionRepository;
        this.alertRepository = alertRepository;
        this.noteRepository = noteRepository;
        this.decisionRepository = decisionRepository;
        this.auditRepository = auditRepository;
        this.searchRepository = searchRepository;
        this.analystActorResolver = analystActorResolver;
        this.metrics = metrics;
        this.updateMutationHandler = updateMutationHandler;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.transactionRunner = transactionRunner;
        this.transitionPolicy = transitionPolicy;
        this.caseAuditService = caseAuditService;
        this.responseMapper = responseMapper;
    }

    public void handleScoredTransaction(TransactionScoredEvent event) {
        if (event.featureSnapshot() == null
                || !Boolean.TRUE.equals(event.featureSnapshot().get("rapidTransferFraudCaseCandidate"))) {
            return;
        }

        List<String> transactionIds = transactionIds(event.featureSnapshot(), event.transactionId());
        String firstTransactionId = transactionIds.isEmpty() ? event.transactionId() : transactionIds.get(0);
        String caseKey = event.customerId() + ":" + SUSPICION_TYPE + ":" + firstTransactionId;
        FraudCaseDocument document = fraudCaseRepository.findByCaseKey(caseKey).orElseGet(() -> newCase(caseKey, event));

        LinkedHashSet<String> mergedIds = new LinkedHashSet<>(document.getTransactionIds() == null ? List.of() : document.getTransactionIds());
        mergedIds.addAll(transactionIds);
        document.setTransactionIds(List.copyOf(mergedIds));
        document.setTotalAmountPln(decimal(event.featureSnapshot().get("rapidTransferTotalPln"), DEFAULT_THRESHOLD_PLN));
        document.setThresholdPln(decimal(event.featureSnapshot().get("rapidTransferThresholdPln"), DEFAULT_THRESHOLD_PLN));
        document.setAggregationWindow(String.valueOf(event.featureSnapshot().getOrDefault("rapidTransferWindow", "PT1M")));
        document.setUpdatedAt(Instant.now());

        List<FraudCaseTransactionDocument> transactions = caseTransactions(document.getTransactionIds(), event);
        document.setTransactions(transactions);
        document.setFirstTransactionAt(firstTransactionAt(transactions, event.transactionTimestamp()));
        document.setLastTransactionAt(lastTransactionAt(transactions, event.transactionTimestamp()));

        fraudCaseRepository.save(document);
    }

    public List<FraudCaseDocument> listCases() {
        return fraudCaseRepository.findAll().stream()
                .map(this::refreshTransactionDetails)
                .toList();
    }

    public Page<FraudCaseDocument> listCases(Pageable pageable) {
        return fraudCaseRepository.findAll(pageable)
                .map(this::refreshTransactionDetails);
    }

    public FraudCaseDocument getCase(String caseId) {
        return refreshTransactionDetails(fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new FraudCaseNotFoundException(caseId)));
    }

    public FraudCaseDocument createCase(CreateFraudCaseRequest request) {
        List<String> alertIds = normalizedIds(request.alertIds());
        transitionPolicy.validateCreate(alertIds, request.priority());
        Set<String> existingAlertIds = alertRepository.findAllById(alertIds).stream()
                .map(alert -> alert.getAlertId())
                .collect(Collectors.toSet());
        List<String> missingAlertIds = alertIds.stream()
                .filter(alertId -> !existingAlertIds.contains(alertId))
                .toList();
        if (!missingAlertIds.isEmpty()) {
            throw new com.frauddetection.alert.exception.AlertNotFoundException(String.join(",", missingAlertIds));
        }
        String actorId = requiredActor(request.actorId(), "CREATE_FRAUD_CASE", "new");
        return transactionRunner.runLocalCommit(() -> {
            Instant now = Instant.now();
            String caseNumber = caseNumber(now);
            FraudCaseDocument document = new FraudCaseDocument();
            document.setCaseId(UUID.randomUUID().toString());
            document.setCaseNumber(caseNumber);
            document.setCaseKey("FDP42:" + caseNumber);
            document.setStatus(FraudCaseStatus.OPEN);
            document.setPriority(request.priority());
            document.setRiskLevel(request.riskLevel());
            document.setLinkedAlertIds(alertIds);
            document.setTransactionIds(List.of());
            document.setReason(StringUtils.hasText(request.reason()) ? request.reason() : "Fraud alerts require investigator workflow.");
            document.setCreatedBy(actorId);
            document.setCreatedAt(now);
            document.setUpdatedAt(now);
            FraudCaseDocument saved = fraudCaseRepository.save(document);
            caseAuditService.append(
                    saved.getCaseId(),
                    actorId,
                    FraudCaseAuditAction.CASE_CREATED,
                    null,
                    saved.getStatus(),
                    Map.of("alertCount", String.valueOf(alertIds.size()), "caseNumber", caseNumber)
            );
            return saved;
        });
    }

    public Page<FraudCaseSummaryResponse> searchCases(
            FraudCaseStatus status,
            String assignee,
            com.frauddetection.alert.domain.FraudCasePriority priority,
            RiskLevel riskLevel,
            Instant createdFrom,
            Instant createdTo,
            String linkedAlertId,
            Pageable pageable
    ) {
        return searchRepository.search(
                new FraudCaseSearchCriteria(status, assignee, priority, riskLevel, createdFrom, createdTo, linkedAlertId),
                pageable
        ).map(responseMapper::toSummary);
    }

    public FraudCaseDocument assignCase(String caseId, AssignFraudCaseRequest request) {
        return transactionRunner.runLocalCommit(() -> {
            FraudCaseDocument document = loadCase(caseId);
            transitionPolicy.validateAssign(document.getStatus(), request.assignedInvestigatorId());
            String actorId = requiredActor(request.actorId(), "ASSIGN_FRAUD_CASE", caseId);
            String previousAssignee = document.getAssignedInvestigatorId();
            Instant now = Instant.now();
            document.setAssignedInvestigatorId(request.assignedInvestigatorId());
            document.setUpdatedAt(now);
            FraudCaseDocument saved = fraudCaseRepository.save(document);
            caseAuditService.append(
                    caseId,
                    actorId,
                    StringUtils.hasText(previousAssignee) ? FraudCaseAuditAction.CASE_REASSIGNED : FraudCaseAuditAction.CASE_ASSIGNED,
                    document.getStatus(),
                    document.getStatus(),
                    Map.of(
                            "previousAssignee", previousAssignee == null ? "" : previousAssignee,
                            "newAssignee", request.assignedInvestigatorId()
                    )
            );
            return saved;
        });
    }

    public FraudCaseNoteResponse addNote(String caseId, AddFraudCaseNoteRequest request) {
        return transactionRunner.runLocalCommit(() -> {
            FraudCaseDocument document = loadCase(caseId);
            transitionPolicy.validateAddNote(document.getStatus(), request.body());
            String actorId = requiredActor(request.actorId(), "ADD_FRAUD_CASE_NOTE", caseId);
            Instant now = Instant.now();
            FraudCaseNoteDocument note = new FraudCaseNoteDocument();
            note.setId(UUID.randomUUID().toString());
            note.setCaseId(caseId);
            note.setBody(request.body());
            note.setInternalOnly(request.internalOnly());
            note.setCreatedBy(actorId);
            note.setCreatedAt(now);
            FraudCaseNoteDocument savedNote = noteRepository.save(note);
            document.setUpdatedAt(now);
            fraudCaseRepository.save(document);
            caseAuditService.append(
                    caseId,
                    actorId,
                    FraudCaseAuditAction.NOTE_ADDED,
                    document.getStatus(),
                    document.getStatus(),
                    Map.of("noteId", savedNote.getId(), "internalOnly", String.valueOf(request.internalOnly()))
            );
            return toNoteResponse(savedNote);
        });
    }

    public FraudCaseDecisionResponse addDecision(String caseId, AddFraudCaseDecisionRequest request) {
        return transactionRunner.runLocalCommit(() -> {
            FraudCaseDocument document = loadCase(caseId);
            transitionPolicy.validateAddDecision(document.getStatus(), request.decisionType(), request.summary());
            String actorId = requiredActor(request.actorId(), "ADD_FRAUD_CASE_DECISION", caseId);
            Instant now = Instant.now();
            FraudCaseDecisionDocument decision = new FraudCaseDecisionDocument();
            decision.setId(UUID.randomUUID().toString());
            decision.setCaseId(caseId);
            decision.setDecisionType(request.decisionType());
            decision.setSummary(request.summary());
            decision.setCreatedBy(actorId);
            decision.setCreatedAt(now);
            FraudCaseDecisionDocument savedDecision = decisionRepository.save(decision);
            document.setUpdatedAt(now);
            fraudCaseRepository.save(document);
            caseAuditService.append(
                    caseId,
                    actorId,
                    FraudCaseAuditAction.DECISION_ADDED,
                    document.getStatus(),
                    document.getStatus(),
                    Map.of("decisionId", savedDecision.getId(), "decisionType", request.decisionType().name())
            );
            return toDecisionResponse(savedDecision);
        });
    }

    public FraudCaseDocument transitionCase(String caseId, TransitionFraudCaseRequest request) {
        return transactionRunner.runLocalCommit(() -> {
            FraudCaseDocument document = loadCase(caseId);
            FraudCaseStatus previousStatus = document.getStatus();
            transitionPolicy.validateTransition(previousStatus, request.targetStatus());
            String actorId = requiredActor(request.actorId(), "TRANSITION_FRAUD_CASE", caseId);
            document.setStatus(request.targetStatus());
            document.setUpdatedAt(Instant.now());
            FraudCaseDocument saved = fraudCaseRepository.save(document);
            caseAuditService.append(caseId, actorId, FraudCaseAuditAction.STATUS_CHANGED, previousStatus, request.targetStatus(), Map.of());
            return saved;
        });
    }

    public FraudCaseDocument closeCase(String caseId, CloseFraudCaseRequest request) {
        return transactionRunner.runLocalCommit(() -> {
            FraudCaseDocument document = loadCase(caseId);
            FraudCaseStatus previousStatus = document.getStatus();
            transitionPolicy.validateClose(previousStatus, request.closureReason());
            String actorId = requiredActor(request.actorId(), "CLOSE_FRAUD_CASE", caseId);
            Instant now = Instant.now();
            document.setStatus(FraudCaseStatus.CLOSED);
            document.setClosureReason(request.closureReason());
            document.setClosedAt(now);
            document.setUpdatedAt(now);
            FraudCaseDocument saved = fraudCaseRepository.save(document);
            caseAuditService.append(
                    caseId,
                    actorId,
                    FraudCaseAuditAction.CASE_CLOSED,
                    previousStatus,
                    FraudCaseStatus.CLOSED,
                    Map.of("reason", request.closureReason())
            );
            return saved;
        });
    }

    public FraudCaseDocument reopenCase(String caseId, ReopenFraudCaseRequest request) {
        return transactionRunner.runLocalCommit(() -> {
            FraudCaseDocument document = loadCase(caseId);
            FraudCaseStatus previousStatus = document.getStatus();
            transitionPolicy.validateReopen(previousStatus, request.reason());
            String actorId = requiredActor(request.actorId(), "REOPEN_FRAUD_CASE", caseId);
            document.setStatus(FraudCaseStatus.REOPENED);
            document.setClosureReason(null);
            document.setClosedAt(null);
            document.setUpdatedAt(Instant.now());
            FraudCaseDocument saved = fraudCaseRepository.save(document);
            caseAuditService.append(
                    caseId,
                    actorId,
                    FraudCaseAuditAction.CASE_REOPENED,
                    previousStatus,
                    FraudCaseStatus.REOPENED,
                    Map.of("reason", request.reason())
            );
            return saved;
        });
    }

    public List<FraudCaseAuditResponse> auditTrail(String caseId) {
        if (!fraudCaseRepository.existsById(caseId)) {
            throw new FraudCaseNotFoundException(caseId);
        }
        return auditRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .map(this::toAuditResponse)
                .toList();
    }

    public UpdateFraudCaseResponse updateCase(String caseId, UpdateFraudCaseRequest request, String idempotencyKey) {
        FraudCaseDocument document = fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new com.frauddetection.alert.exception.AlertNotFoundException(caseId));
        String actorId = analystActorResolver.resolveActorId(request.analystId(), "UPDATE_FRAUD_CASE", caseId);
        String correlationId = correlationId(document);
        String requestHash = requestHash(request, actorId);
        String idempotencyKeyHash = RegulatedMutationIntentHasher.hash(idempotencyKey);
        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.fraudCaseUpdate(
                caseId,
                actorId,
                request.status(),
                actorId,
                request.decisionReason(),
                request.tags(),
                payload(request, actorId)
        );
        RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                caseId,
                AuditResourceType.FRAUD_CASE,
                AuditAction.UPDATE_FRAUD_CASE,
                correlationId,
                requestHash,
                context -> updateMutationHandler.update(caseId, request, actorId),
                (saved, state) -> committedResponse(saved, idempotencyKeyHash, publicStatus(state)),
                RegulatedMutationResponseSnapshot::fromUpdateFraudCaseResponse,
                RegulatedMutationResponseSnapshot::toUpdateFraudCaseResponse,
                state -> statusResponse(caseId, document, idempotencyKeyHash, state),
                intent
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    public UpdateFraudCaseResponse updateCase(String caseId, UpdateFraudCaseRequest request) {
        return updateCase(caseId, request, null);
    }

    private FraudCaseDocument loadCase(String caseId) {
        return fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new FraudCaseNotFoundException(caseId));
    }

    private String requiredActor(String requestActorId, String action, String resourceId) {
        String actorId = analystActorResolver.resolveActorId(requestActorId, action, resourceId);
        if (!StringUtils.hasText(actorId)) {
            throw new FraudCaseActorUnavailableException();
        }
        return actorId;
    }

    private List<String> normalizedIds(List<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String caseNumber(Instant now) {
        String date = DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC).format(now);
        return "FC-" + date + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private FraudCaseAuditResponse toAuditResponse(FraudCaseAuditEntryDocument document) {
        return new FraudCaseAuditResponse(
                document.getId(),
                document.getCaseId(),
                document.getAction(),
                document.getActorId(),
                document.getOccurredAt(),
                document.getPreviousStatus(),
                document.getNewStatus(),
                document.getDetails() == null ? Map.of() : document.getDetails()
        );
    }

    private FraudCaseNoteResponse toNoteResponse(FraudCaseNoteDocument document) {
        return new FraudCaseNoteResponse(
                document.getId(),
                document.getCaseId(),
                document.getBody(),
                document.getCreatedBy(),
                document.getCreatedAt(),
                document.isInternalOnly()
        );
    }

    private FraudCaseDecisionResponse toDecisionResponse(FraudCaseDecisionDocument document) {
        return new FraudCaseDecisionResponse(
                document.getId(),
                document.getCaseId(),
                document.getDecisionType(),
                document.getSummary(),
                document.getCreatedBy(),
                document.getCreatedAt()
        );
    }

    private String correlationId(FraudCaseDocument document) {
        return (document.getTransactions() == null ? List.<FraudCaseTransactionDocument>of() : document.getTransactions()).stream()
                .map(FraudCaseTransactionDocument::getCorrelationId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private UpdateFraudCaseResponse committedResponse(
            FraudCaseDocument saved,
            String idempotencyKeyHash,
            SubmitDecisionOperationStatus status
    ) {
        return new UpdateFraudCaseResponse(
                status,
                null,
                idempotencyKeyHash,
                saved.getCaseId(),
                null,
                responseMapper.toResponse(saved),
                null
        );
    }

    private UpdateFraudCaseResponse statusResponse(
            String caseId,
            FraudCaseDocument current,
            String idempotencyKeyHash,
            RegulatedMutationState state
    ) {
        SubmitDecisionOperationStatus status = publicStatus(state);
        return new UpdateFraudCaseResponse(
                status,
                null,
                idempotencyKeyHash,
                caseId,
                responseMapper.toResponse(current),
                null,
                status == SubmitDecisionOperationStatus.RECOVERY_REQUIRED || status == SubmitDecisionOperationStatus.COMMIT_UNKNOWN
                        ? state.name()
                        : null
        );
    }

    private SubmitDecisionOperationStatus publicStatus(RegulatedMutationState state) {
        return switch (state) {
            case REQUESTED, AUDIT_ATTEMPTED -> SubmitDecisionOperationStatus.IN_PROGRESS;
            case EVIDENCE_PREPARING -> SubmitDecisionOperationStatus.EVIDENCE_PREPARING;
            case EVIDENCE_PREPARED -> SubmitDecisionOperationStatus.EVIDENCE_PREPARED;
            case FINALIZING -> SubmitDecisionOperationStatus.FINALIZING;
            case FINALIZED_VISIBLE -> SubmitDecisionOperationStatus.FINALIZED_VISIBLE;
            case FINALIZED_EVIDENCE_PENDING_EXTERNAL -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL;
            case FINALIZED_EVIDENCE_CONFIRMED -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED;
            case REJECTED_EVIDENCE_UNAVAILABLE -> SubmitDecisionOperationStatus.REJECTED_EVIDENCE_UNAVAILABLE;
            case FAILED_BUSINESS_VALIDATION -> SubmitDecisionOperationStatus.FAILED_BUSINESS_VALIDATION;
            case FINALIZE_RECOVERY_REQUIRED -> SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED;
            case BUSINESS_COMMITTING -> SubmitDecisionOperationStatus.COMMIT_UNKNOWN;
            case EVIDENCE_PENDING, COMMITTED, SUCCESS_AUDIT_RECORDED ->
                    SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
            case EVIDENCE_CONFIRMED -> SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED;
            case COMMITTED_DEGRADED -> SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE;
            case FAILED, BUSINESS_COMMITTED, SUCCESS_AUDIT_PENDING -> SubmitDecisionOperationStatus.RECOVERY_REQUIRED;
            case REJECTED -> SubmitDecisionOperationStatus.REJECTED_BEFORE_MUTATION;
        };
    }

    private String requestHash(UpdateFraudCaseRequest request, String actorId) {
        return RegulatedMutationIntentHasher.hash(payload(request, actorId));
    }

    private String payload(UpdateFraudCaseRequest request, String actorId) {
        return "status=" + RegulatedMutationIntentHasher.canonicalValue(request.status())
                + "|analystId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                + "|decisionReason=" + RegulatedMutationIntentHasher.canonicalValue(request.decisionReason())
                + "|tags=" + RegulatedMutationIntentHasher.canonicalValue(request.tags());
    }

    private FraudCaseDocument newCase(String caseKey, TransactionScoredEvent event) {
        Instant now = Instant.now();
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(UUID.randomUUID().toString());
        document.setCaseKey(caseKey);
        document.setCustomerId(event.customerId());
        document.setSuspicionType(SUSPICION_TYPE);
        document.setStatus(FraudCaseStatus.OPEN);
        document.setReason("Multiple transfers exceeded 20000 PLN equivalent inside a short time window.");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        document.setFirstTransactionAt(event.transactionTimestamp());
        document.setTransactions(List.of());
        document.setTransactionIds(List.of());
        return document;
    }

    private FraudCaseTransactionDocument toCaseTransaction(TransactionScoredEvent event) {
        FraudCaseTransactionDocument document = new FraudCaseTransactionDocument();
        document.setTransactionId(event.transactionId());
        document.setCorrelationId(event.correlationId());
        document.setTransactionTimestamp(event.transactionTimestamp());
        document.setTransactionAmount(event.transactionAmount());
        document.setAmountPln(decimal(event.featureSnapshot().get("currentTransactionAmountPln"), BigDecimal.ZERO));
        document.setFraudScore(event.fraudScore());
        document.setRiskLevel(event.riskLevel());
        return document;
    }

    private FraudCaseTransactionDocument toCaseTransaction(ScoredTransactionDocument scoredTransaction) {
        FraudCaseTransactionDocument document = new FraudCaseTransactionDocument();
        document.setTransactionId(scoredTransaction.getTransactionId());
        document.setCorrelationId(scoredTransaction.getCorrelationId());
        document.setTransactionTimestamp(scoredTransaction.getTransactionTimestamp());
        document.setTransactionAmount(scoredTransaction.getTransactionAmount());
        document.setAmountPln(toPln(scoredTransaction.getTransactionAmount()));
        document.setFraudScore(scoredTransaction.getFraudScore());
        document.setRiskLevel(scoredTransaction.getRiskLevel());
        return document;
    }

    private FraudCaseDocument refreshTransactionDetails(FraudCaseDocument document) {
        List<String> transactionIds = document.getTransactionIds() == null ? List.of() : document.getTransactionIds();
        if (transactionIds.isEmpty()) {
            return document;
        }

        List<String> existingIds = document.getTransactions() == null
                ? List.of()
                : document.getTransactions().stream().map(FraudCaseTransactionDocument::getTransactionId).toList();
        if (existingIds.size() == transactionIds.size() && existingIds.containsAll(transactionIds)) {
            return document;
        }

        List<FraudCaseTransactionDocument> transactions = caseTransactions(transactionIds, null);
        if (transactions.isEmpty()) {
            return document;
        }

        document.setTransactions(transactions);
        document.setFirstTransactionAt(firstTransactionAt(transactions, document.getFirstTransactionAt()));
        document.setLastTransactionAt(lastTransactionAt(transactions, document.getLastTransactionAt()));
        document.setUpdatedAt(Instant.now());
        return fraudCaseRepository.save(document);
    }

    private List<FraudCaseTransactionDocument> caseTransactions(List<String> transactionIds, TransactionScoredEvent currentEvent) {
        Map<String, ScoredTransactionDocument> storedTransactions = scoredTransactionRepository.findAllById(transactionIds)
                .stream()
                .collect(Collectors.toMap(ScoredTransactionDocument::getTransactionId, transaction -> transaction));

        List<FraudCaseTransactionDocument> transactions = new ArrayList<>();
        for (String transactionId : transactionIds) {
            if (currentEvent != null && currentEvent.transactionId().equals(transactionId)) {
                transactions.add(toCaseTransaction(currentEvent));
            } else if (storedTransactions.containsKey(transactionId)) {
                transactions.add(toCaseTransaction(storedTransactions.get(transactionId)));
            }
        }
        return List.copyOf(transactions);
    }

    private Instant firstTransactionAt(List<FraudCaseTransactionDocument> transactions, Instant fallback) {
        return transactions.stream()
                .map(FraudCaseTransactionDocument::getTransactionTimestamp)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(fallback);
    }

    private Instant lastTransactionAt(List<FraudCaseTransactionDocument> transactions, Instant fallback) {
        return transactions.stream()
                .map(FraudCaseTransactionDocument::getTransactionTimestamp)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(fallback);
    }

    private List<String> transactionIds(Map<String, Object> featureSnapshot, String currentTransactionId) {
        Object value = featureSnapshot.get("rapidTransferTransactionIds");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(currentTransactionId);
    }

    private BigDecimal decimal(Object value, BigDecimal defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private BigDecimal toPln(com.frauddetection.common.events.model.Money money) {
        if (money == null || money.amount() == null) {
            return BigDecimal.ZERO;
        }
        String currency = money.currency() == null ? "PLN" : money.currency().toUpperCase(Locale.ROOT);
        BigDecimal rate = switch (currency) {
            case "EUR" -> BigDecimal.valueOf(4.30d);
            case "USD" -> BigDecimal.valueOf(4.00d);
            case "GBP" -> BigDecimal.valueOf(5.00d);
            default -> BigDecimal.ONE;
        };
        return money.amount().multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
