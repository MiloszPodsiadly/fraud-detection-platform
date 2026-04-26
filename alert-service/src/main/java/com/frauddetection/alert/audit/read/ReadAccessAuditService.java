package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
        try {
            ReadAccessAuditEvent event = event(target, outcome, resultCount, correlationId);
            repository.save(ReadAccessAuditEventDocument.from(event));
            metrics.recordReadAccessAuditPersisted(target.endpointCategory(), outcome);
        } catch (RuntimeException exception) {
            metrics.recordReadAccessAuditPersistenceFailure(target.endpointCategory());
            log.atWarn()
                    .addKeyValue("endpointCategory", target.endpointCategory())
                    .addKeyValue("resourceType", target.resourceType())
                    .addKeyValue("outcome", outcome)
                    .addKeyValue("errorType", exception.getClass().getSimpleName())
                    .log("Read-access audit persistence failed; sensitive read response was not blocked.");
        }
    }

    private ReadAccessAuditEvent event(
            ReadAccessAuditTarget target,
            ReadAccessAuditOutcome outcome,
            int resultCount,
            String correlationId
    ) {
        AnalystPrincipal principal = currentAnalystUser.get().orElse(null);
        return new ReadAccessAuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                principal == null ? "unknown" : principal.userId(),
                principal == null ? Set.of() : roles(principal),
                ReadAccessAuditAction.READ,
                target.resourceType(),
                target.resourceId(),
                target.endpointCategory(),
                target.queryHash(),
                target.page(),
                target.size(),
                Math.max(0, Math.min(resultCount, MAX_RESULT_COUNT)),
                outcome,
                normalize(correlationId),
                "alert-service",
                SCHEMA_VERSION
        );
    }

    private Set<String> roles(AnalystPrincipal principal) {
        TreeSet<String> roles = new TreeSet<>();
        principal.roles().forEach(role -> roles.add(role.name()));
        return roles;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
