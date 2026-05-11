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
import com.frauddetection.alert.fraudcase.FraudCaseLifecycleIdempotencyCommand;
import com.frauddetection.alert.fraudcase.FraudCaseLifecycleIdempotencyService;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.fraudcase.FraudCaseTransitionPolicy;
import com.frauddetection.alert.idempotency.IdempotencyCanonicalHasher;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseDecisionDocument;
import com.frauddetection.alert.persistence.FraudCaseDecisionRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
    private final FraudCaseLifecycleIdempotencyService idempotencyService;

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
        this(
                fraudCaseRepository,
                alertRepository,
                noteRepository,
                decisionRepository,
                analystActorResolver,
                transactionRunner,
                transitionPolicy,
                caseAuditService,
                null
        );
    }

    @Autowired
    public FraudCaseLifecycleService(
            FraudCaseRepository fraudCaseRepository,
            AlertRepository alertRepository,
            FraudCaseNoteRepository noteRepository,
            FraudCaseDecisionRepository decisionRepository,
            AnalystActorResolver analystActorResolver,
            RegulatedMutationTransactionRunner transactionRunner,
            FraudCaseTransitionPolicy transitionPolicy,
            FraudCaseAuditService caseAuditService,
            FraudCaseLifecycleIdempotencyService idempotencyService
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.alertRepository = alertRepository;
        this.noteRepository = noteRepository;
        this.decisionRepository = decisionRepository;
        this.analystActorResolver = analystActorResolver;
        this.transactionRunner = transactionRunner;
        this.transitionPolicy = transitionPolicy;
        this.caseAuditService = caseAuditService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Internal/backward-compatibility path only. Public HTTP lifecycle POST endpoints must use
     * idempotency-key overloads. Guarded by the FDP-43 public-path architecture test.
     */
    @Deprecated(forRemoval = false)
    public FraudCaseDocument createCase(CreateFraudCaseRequest request) {
        return createCase(request, null, false);
    }

    public FraudCaseDocument createCase(CreateFraudCaseRequest request, String idempotencyKey) {
        return createCase(request, idempotencyKey, true);
    }

    private FraudCaseDocument createCase(CreateFraudCaseRequest request, String idempotencyKey, boolean requireIdempotency) {
        List<String> alertIds = normalizedIds(request.alertIds());
        if (!requireIdempotency) {
            transitionPolicy.validateCreate(alertIds, request.priority());
        }
        String actorId = requiredActor(request.actorId(), "CREATE_FRAUD_CASE", "new");
        return execute(command(
                idempotencyKey,
                "CREATE_FRAUD_CASE",
                actorId,
                "CREATE",
                payload("alertIds", alertIds, "priority", request.priority(), "riskLevel", request.riskLevel(), "reason", request.reason())
        ), () -> {
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
        }, FraudCaseDocument.class, requireIdempotency);
    }

    /**
     * Internal/backward-compatibility path only. Public HTTP lifecycle POST endpoints must use
     * idempotency-key overloads. Guarded by the FDP-43 public-path architecture test.
     */
    @Deprecated(forRemoval = false)
    public FraudCaseDocument assignCase(String caseId, AssignFraudCaseRequest request) {
        return assignCase(caseId, request, null, false);
    }

    public FraudCaseDocument assignCase(String caseId, AssignFraudCaseRequest request, String idempotencyKey) {
        return assignCase(caseId, request, idempotencyKey, true);
    }

    private FraudCaseDocument assignCase(String caseId, AssignFraudCaseRequest request, String idempotencyKey, boolean requireIdempotency) {
        String actorId = requiredActor(request.actorId(), "ASSIGN_FRAUD_CASE", caseId);
        return execute(command(
                idempotencyKey,
                "ASSIGN_FRAUD_CASE",
                actorId,
                caseId,
                payload("assignedInvestigatorId", request.assignedInvestigatorId())
        ), () -> {
            FraudCaseDocument document = loadCase(caseId);
            transitionPolicy.validateAssign(document.getStatus(), request.assignedInvestigatorId());
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
        }, FraudCaseDocument.class, requireIdempotency);
    }

    /**
     * Internal/backward-compatibility path only. Public HTTP lifecycle POST endpoints must use
     * idempotency-key overloads. Guarded by the FDP-43 public-path architecture test.
     */
    @Deprecated(forRemoval = false)
    public FraudCaseNoteResponse addNote(String caseId, AddFraudCaseNoteRequest request) {
        return addNote(caseId, request, null, false);
    }

    public FraudCaseNoteResponse addNote(String caseId, AddFraudCaseNoteRequest request, String idempotencyKey) {
        return addNote(caseId, request, idempotencyKey, true);
    }

    private FraudCaseNoteResponse addNote(String caseId, AddFraudCaseNoteRequest request, String idempotencyKey, boolean requireIdempotency) {
        if (!requireIdempotency) {
            FraudCaseDocument document = loadCase(caseId);
            transitionPolicy.validateAddNote(document.getStatus(), request.body());
        }
        String actorId = requiredActor(request.actorId(), "ADD_FRAUD_CASE_NOTE", caseId);
        return execute(command(
                idempotencyKey,
                "ADD_FRAUD_CASE_NOTE",
                actorId,
                caseId,
                payload("body", request.body(), "internalOnly", request.internalOnly())
        ), () -> {
            FraudCaseDocument document = loadCase(caseId);
            transitionPolicy.validateAddNote(document.getStatus(), request.body());
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
        }, FraudCaseNoteResponse.class, requireIdempotency);
    }

    /**
     * Internal/backward-compatibility path only. Public HTTP lifecycle POST endpoints must use
     * idempotency-key overloads. Guarded by the FDP-43 public-path architecture test.
     */
    @Deprecated(forRemoval = false)
    public FraudCaseDecisionResponse addDecision(String caseId, AddFraudCaseDecisionRequest request) {
        return addDecision(caseId, request, null, false);
    }

    public FraudCaseDecisionResponse addDecision(String caseId, AddFraudCaseDecisionRequest request, String idempotencyKey) {
        return addDecision(caseId, request, idempotencyKey, true);
    }

    private FraudCaseDecisionResponse addDecision(String caseId, AddFraudCaseDecisionRequest request, String idempotencyKey, boolean requireIdempotency) {
        String actorId = requiredActor(request.actorId(), "ADD_FRAUD_CASE_DECISION", caseId);
        return execute(command(
                idempotencyKey,
                "ADD_FRAUD_CASE_DECISION",
                actorId,
                caseId,
                payload("decisionType", request.decisionType(), "summary", request.summary())
        ), () -> {
            FraudCaseDocument document = loadCase(caseId);
            transitionPolicy.validateAddDecision(document.getStatus(), request.decisionType(), request.summary());
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
        }, FraudCaseDecisionResponse.class, requireIdempotency);
    }

    /**
     * Internal/backward-compatibility path only. Public HTTP lifecycle POST endpoints must use
     * idempotency-key overloads. Guarded by the FDP-43 public-path architecture test.
     */
    @Deprecated(forRemoval = false)
    public FraudCaseDocument transitionCase(String caseId, TransitionFraudCaseRequest request) {
        return transitionCase(caseId, request, null, false);
    }

    public FraudCaseDocument transitionCase(String caseId, TransitionFraudCaseRequest request, String idempotencyKey) {
        return transitionCase(caseId, request, idempotencyKey, true);
    }

    private FraudCaseDocument transitionCase(String caseId, TransitionFraudCaseRequest request, String idempotencyKey, boolean requireIdempotency) {
        String actorId = requiredActor(request.actorId(), "TRANSITION_FRAUD_CASE", caseId);
        return execute(command(
                idempotencyKey,
                "TRANSITION_FRAUD_CASE",
                actorId,
                caseId,
                payload("targetStatus", request.targetStatus())
        ), () -> {
            FraudCaseDocument document = loadCase(caseId);
            FraudCaseStatus previousStatus = document.getStatus();
            transitionPolicy.validateTransition(previousStatus, request.targetStatus());
            document.setStatus(request.targetStatus());
            document.setUpdatedAt(Instant.now());
            FraudCaseDocument saved = fraudCaseRepository.save(document);
            caseAuditService.append(caseId, actorId, FraudCaseAuditAction.STATUS_CHANGED, previousStatus, request.targetStatus(), Map.of());
            return saved;
        }, FraudCaseDocument.class, requireIdempotency);
    }

    /**
     * Internal/backward-compatibility path only. Public HTTP lifecycle POST endpoints must use
     * idempotency-key overloads. Guarded by the FDP-43 public-path architecture test.
     */
    @Deprecated(forRemoval = false)
    public FraudCaseDocument closeCase(String caseId, CloseFraudCaseRequest request) {
        return closeCase(caseId, request, null, false);
    }

    public FraudCaseDocument closeCase(String caseId, CloseFraudCaseRequest request, String idempotencyKey) {
        return closeCase(caseId, request, idempotencyKey, true);
    }

    private FraudCaseDocument closeCase(String caseId, CloseFraudCaseRequest request, String idempotencyKey, boolean requireIdempotency) {
        String actorId = requiredActor(request.actorId(), "CLOSE_FRAUD_CASE", caseId);
        return execute(command(
                idempotencyKey,
                "CLOSE_FRAUD_CASE",
                actorId,
                caseId,
                payload("closureReason", request.closureReason())
        ), () -> {
            FraudCaseDocument document = loadCase(caseId);
            FraudCaseStatus previousStatus = document.getStatus();
            transitionPolicy.validateClose(previousStatus, request.closureReason());
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
        }, FraudCaseDocument.class, requireIdempotency);
    }

    /**
     * Internal/backward-compatibility path only. Public HTTP lifecycle POST endpoints must use
     * idempotency-key overloads. Guarded by the FDP-43 public-path architecture test.
     */
    @Deprecated(forRemoval = false)
    public FraudCaseDocument reopenCase(String caseId, ReopenFraudCaseRequest request) {
        return reopenCase(caseId, request, null, false);
    }

    public FraudCaseDocument reopenCase(String caseId, ReopenFraudCaseRequest request, String idempotencyKey) {
        return reopenCase(caseId, request, idempotencyKey, true);
    }

    private FraudCaseDocument reopenCase(String caseId, ReopenFraudCaseRequest request, String idempotencyKey, boolean requireIdempotency) {
        String actorId = requiredActor(request.actorId(), "REOPEN_FRAUD_CASE", caseId);
        return execute(command(
                idempotencyKey,
                "REOPEN_FRAUD_CASE",
                actorId,
                caseId,
                payload("reason", request.reason())
        ), () -> {
            FraudCaseDocument document = loadCase(caseId);
            FraudCaseStatus previousStatus = document.getStatus();
            transitionPolicy.validateReopen(previousStatus, request.reason());
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
        }, FraudCaseDocument.class, requireIdempotency);
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

    private FraudCaseLifecycleIdempotencyCommand command(
            String idempotencyKey,
            String action,
            String actorId,
            String caseIdScope,
            Map<String, Object> payload
    ) {
        Map<String, Object> scopedPayload = new LinkedHashMap<>(payload);
        scopedPayload.put("action", action);
        scopedPayload.put("actorId", actorId);
        scopedPayload.put("caseIdScope", caseIdScope);
        return new FraudCaseLifecycleIdempotencyCommand(
                idempotencyKey,
                action,
                actorId,
                caseIdScope,
                IdempotencyCanonicalHasher.hash(scopedPayload),
                Instant.now()
        );
    }

    private <T> T execute(
            FraudCaseLifecycleIdempotencyCommand command,
            java.util.function.Supplier<T> mutation,
            Class<T> responseType,
            boolean requireIdempotency
    ) {
        if (!requireIdempotency) {
            return transactionRunner.runLocalCommit(mutation);
        }
        if (idempotencyService == null) {
            throw new IllegalStateException("Fraud-case lifecycle idempotency is required but not configured.");
        }
        return idempotencyService.execute(command, mutation, responseType);
    }

    private Map<String, Object> payload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            payload.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return payload;
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
