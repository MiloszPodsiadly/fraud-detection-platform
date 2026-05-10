package com.frauddetection.alert.service;

import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.AssignFraudCaseRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseActorUnavailableException;
import com.frauddetection.alert.fraudcase.FraudCaseAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.fraudcase.FraudCaseTransitionPolicy;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseDecisionDocument;
import com.frauddetection.alert.persistence.FraudCaseDecisionRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FraudCaseLifecycleService {

    private final FraudCaseRepository fraudCaseRepository;
    private final AlertRepository alertRepository;
    private final FraudCaseNoteRepository noteRepository;
    private final FraudCaseDecisionRepository decisionRepository;
    private final AnalystActorResolver analystActorResolver;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final FraudCaseTransitionPolicy transitionPolicy;
    private final FraudCaseAuditService caseAuditService;

    public FraudCaseLifecycleService(
            FraudCaseRepository fraudCaseRepository,
            AlertRepository alertRepository,
            FraudCaseNoteRepository noteRepository,
            FraudCaseDecisionRepository decisionRepository,
            AnalystActorResolver analystActorResolver,
            RegulatedMutationTransactionRunner transactionRunner,
            FraudCaseTransitionPolicy transitionPolicy,
            FraudCaseAuditService caseAuditService
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.alertRepository = alertRepository;
        this.noteRepository = noteRepository;
        this.decisionRepository = decisionRepository;
        this.analystActorResolver = analystActorResolver;
        this.transactionRunner = transactionRunner;
        this.transitionPolicy = transitionPolicy;
        this.caseAuditService = caseAuditService;
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

    private String caseNumber(Instant now) {
        String date = DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC).format(now);
        return "FC-" + date + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
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
}
