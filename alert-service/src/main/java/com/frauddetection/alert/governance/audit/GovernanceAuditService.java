package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GovernanceAuditService {

    private final GovernanceAuditRepository repository;
    private final GovernanceAdvisoryClient advisoryClient;
    private final CurrentAnalystUser currentAnalystUser;
    private final GovernanceAuditProperties properties;
    private final GovernanceAuditRequestValidator requestValidator;

    public GovernanceAuditService(
            GovernanceAuditRepository repository,
            GovernanceAdvisoryClient advisoryClient,
            CurrentAnalystUser currentAnalystUser,
            GovernanceAuditProperties properties,
            GovernanceAuditRequestValidator requestValidator
    ) {
        this.repository = repository;
        this.advisoryClient = advisoryClient;
        this.currentAnalystUser = currentAnalystUser;
        this.properties = properties;
        this.requestValidator = requestValidator;
    }

    public GovernanceAuditEventResponse appendAudit(String advisoryEventId, GovernanceAuditRequest request) {
        GovernanceAuditCommand command = requestValidator.validate(request);
        GovernanceAdvisorySnapshot advisory = advisoryClient.getAdvisory(advisoryEventId);
        AnalystPrincipal actor = currentAnalystUser.get()
                .orElseThrow(GovernanceAuditActorUnavailableException::new);

        GovernanceAuditEventDocument document = new GovernanceAuditEventDocument();
        document.setAuditId(UUID.randomUUID().toString());
        document.setAdvisoryEventId(advisory.eventId());
        document.setDecision(command.decision());
        document.setNote(command.note());
        document.setActorId(actor.userId());
        document.setActorDisplayName(actor.userId());
        document.setActorRoles(actor.roles().stream().map(Enum::name).sorted().toList());
        document.setCreatedAt(Instant.now());
        document.setModelName(advisory.modelName());
        document.setModelVersion(advisory.modelVersion());
        document.setAdvisorySeverity(advisory.severity());
        document.setAdvisoryConfidence(advisory.confidence());
        document.setAdvisoryConfidenceContext(advisory.advisoryConfidenceContext());

        try {
            GovernanceAuditEventDocument saved = repository.save(document);
            return GovernanceAuditEventResponse.from(saved);
        } catch (DataAccessException exception) {
            throw new GovernanceAuditPersistenceUnavailableException();
        }
    }

    public GovernanceAuditHistoryResponse history(String advisoryEventId) {
        try {
            List<GovernanceAuditEventResponse> events = repository
                    .findByAdvisoryEventIdOrderByCreatedAtDesc(
                            advisoryEventId,
                            PageRequest.of(0, properties.historyLimit())
                    )
                    .stream()
                    .map(GovernanceAuditEventResponse::from)
                    .toList();
            return new GovernanceAuditHistoryResponse(advisoryEventId, "AVAILABLE", events);
        } catch (DataAccessException exception) {
            return new GovernanceAuditHistoryResponse(advisoryEventId, "UNAVAILABLE", List.of());
        }
    }

}
