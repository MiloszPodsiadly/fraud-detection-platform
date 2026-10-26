package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class ReadAccessAuditService {

    private static final Logger log = LoggerFactory.getLogger(ReadAccessAuditService.class);
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_RESULT_COUNT = 100;

    private final ReadAccessAuditRepository repository;
    private final CurrentAnalystUser currentAnalystUser;
    private final AlertServiceMetrics metrics;

    public ReadAccessAuditService(
            ReadAccessAuditRepository repository,
            CurrentAnalystUser currentAnalystUser,
            AlertServiceMetrics metrics
    ) {
        this.repository = repository;
        this.currentAnalystUser = currentAnalystUser;
        this.metrics = metrics;
    }

    public void audit(ReadAccessAuditTarget target, ReadAccessAuditOutcome outcome, int resultCount, String correlationId) {
        audit(target, outcome, resultCount, correlationId, false);
    }

    public void auditOrThrow(ReadAccessAuditTarget target, ReadAccessAuditOutcome outcome, int resultCount, String correlationId) {
        audit(target, outcome, resultCount, correlationId, true);
    }

    private void audit(
            ReadAccessAuditTarget target,
            ReadAccessAuditOutcome outcome,
            int resultCount,
            String correlationId,
            boolean failClosed
    ) {
        ReadAccessAuditEvent event = event(target, outcome, resultCount, correlationId, failClosed);
        try {
            repository.save(ReadAccessAuditEventDocument.from(event));
        } catch (RuntimeException exception) {
            bestEffort(() -> metrics.recordReadAccessAuditPersistenceFailure(target.endpointCategory()));
            log.atWarn()
                    .addKeyValue("endpointCategory", target.endpointCategory())
                    .addKeyValue("resourceType", target.resourceType())
                    .addKeyValue("outcome", outcome)
                    .addKeyValue("errorType", exception.getClass().getSimpleName())
                    .addKeyValue("failClosed", failClosed)
                    .log("Read-access audit persistence failed.");
            if (failClosed) {
                throw exception;
            }
            return;
        }
        bestEffort(() -> metrics.recordReadAccessAuditPersisted(target.endpointCategory(), outcome));
    }

    private ReadAccessAuditEvent event(
            ReadAccessAuditTarget target,
            ReadAccessAuditOutcome outcome,
            int resultCount,
            String correlationId,
            boolean requireActor
    ) {
        Optional<AnalystPrincipal> principalOptional = currentAnalystUser.get();
        AnalystPrincipal principal = principalOptional == null ? null : principalOptional.orElse(null);
        String actorId = resolveActorId(principal, requireActor);
        if (actorId == null) {
            bestEffort(() -> metrics.recordReadAccessAuditActorMissing(target.endpointCategory()));
            log.atWarn()
                    .addKeyValue("endpointCategory", target.endpointCategory())
                    .addKeyValue("resourceType", target.resourceType())
                    .log("Read-access audit actor principal is missing or invalid.");
            if (requireActor) {
                throw new MissingReadAccessActorException();
            }
        }
        return new ReadAccessAuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                actorId == null ? "unknown" : actorId,
                principal == null ? Set.of() : roles(principal),
                ReadAccessAuditAction.READ,
                target.resourceType(),
                target.resourceId(),
                target.endpointCategory(),
                target.queryHash(),
                target.filterBucket(),
                target.page(),
                target.size(),
                Math.max(0, Math.min(resultCount, MAX_RESULT_COUNT)),
                outcome,
                normalize(correlationId),
                "alert-service",
                SCHEMA_VERSION
        );
    }

    private void bestEffort(Runnable telemetry) {
        try {
            telemetry.run();
        } catch (RuntimeException ignored) {
            // Metrics are advisory; persistence and fail-closed actor checks remain authoritative.
        }
    }

    private String resolveActorId(AnalystPrincipal principal, boolean requireTrustedActor) {
        if (principal != null && principal.readAuditActorId() != null
                && !principal.readAuditActorId().isBlank()) {
            return principal.readAuditActorId();
        }
        if (principal != null && principal.institutionalActorId() != null) {
            int separator = principal.institutionalActorId().lastIndexOf('#');
            if (separator > 0 && separator < principal.institutionalActorId().length() - 1) {
                try {
                    return PolicyGovernanceActor.identityId(
                            principal.institutionalActorId().substring(0, separator),
                            principal.institutionalActorId().substring(separator + 1));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        if (principal == null || principal.userId() == null || principal.userId().isBlank()) {
            return null;
        }
        if (requireTrustedActor) {
            return null;
        }
        return "unverified:" + PolicyGovernanceActor.identityId("unverified-user-id",
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(principal.userId().trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .substring("oidc:".length());
    }

    private Set<String> roles(AnalystPrincipal principal) {
        TreeSet<String> roles = new TreeSet<>();
        principal.roles().forEach(role -> roles.add(role.name()));
        return roles;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class MissingReadAccessActorException extends IllegalStateException {

        private MissingReadAccessActorException() {
            super("Sensitive read actor identity is unavailable.");
        }
    }
}
