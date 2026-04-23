package com.frauddetection.alert.audit;

import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
public class AuditService {

    private final CurrentAnalystUser currentAnalystUser;
    private final AuditEventPublisher auditEventPublisher;

    public AuditService(CurrentAnalystUser currentAnalystUser, AuditEventPublisher auditEventPublisher) {
        this.currentAnalystUser = currentAnalystUser;
        this.auditEventPublisher = auditEventPublisher;
    }

    public void audit(
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String correlationId,
            String fallbackActorId
    ) {
        audit(action, resourceType, resourceId, correlationId, fallbackActorId, AuditOutcome.SUCCESS, null);
    }

    public void audit(
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String correlationId,
            String fallbackActorId,
            AuditOutcome outcome,
            String failureReason
    ) {
        AuditEvent event = new AuditEvent(
                actor(fallbackActorId),
                Objects.requireNonNull(action, "action must not be null"),
                Objects.requireNonNull(resourceType, "resourceType must not be null"),
                resourceId,
                Instant.now(),
                normalizeOptional(correlationId),
                Objects.requireNonNull(outcome, "outcome must not be null"),
                normalizeOptional(failureReason)
        );
        auditEventPublisher.publish(event);
    }

    private AuditActor actor(String fallbackActorId) {
        return currentAnalystUser.get()
                .map(this::actor)
                .orElseGet(() -> new AuditActor(fallbackActorId, Set.of(), Set.of()));
    }

    private AuditActor actor(AnalystPrincipal principal) {
        Set<String> roles = principal.roles().stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> authorities = new TreeSet<>(principal.authorities());
        return new AuditActor(principal.userId(), roles, authorities);
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
