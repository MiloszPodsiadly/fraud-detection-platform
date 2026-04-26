package com.frauddetection.alert.observability;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.security.error.SecurityFailureClassifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AlertServiceMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger governanceAnalyticsWindowDays = new AtomicInteger(0);

    public AlertServiceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("fraud_ml_governance_analytics_window_days", governanceAnalyticsWindowDays, AtomicInteger::get)
                .register(meterRegistry);
    }

    public void recordAnalystDecisionSubmitted() {
        counter("fraud.alert.decision.submissions", "outcome", "success").increment();
    }

    public void recordFraudCaseUpdated() {
        counter("fraud.alert.fraud_case.updates", "outcome", "success").increment();
    }

    public void recordAuditEventPublished(AuditAction action, AuditOutcome outcome) {
        counter(
                "fraud.alert.audit.events",
                "action", normalize(action),
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordPlatformAuditEventPersisted(AuditAction eventType, AuditOutcome outcome) {
        counter(
                "fraud_platform_audit_events_persisted_total",
                "event_type", normalize(eventType),
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordPlatformAuditPersistenceFailure(AuditAction eventType) {
        counter(
                "fraud_platform_audit_persistence_failures_total",
                "event_type", normalize(eventType)
        ).increment();
    }

    public void recordPlatformAuditReadRequest(String status) {
        counter(
                "fraud_platform_audit_read_requests_total",
                "status", normalizeAvailabilityStatus(status)
        ).increment();
    }

    public void recordReadAccessAuditPersisted(ReadAccessEndpointCategory endpointCategory, ReadAccessAuditOutcome outcome) {
        counter(
                "fraud_platform_read_access_audit_events_persisted_total",
                "endpoint_category", normalize(endpointCategory),
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordReadAccessAuditPersistenceFailure(ReadAccessEndpointCategory endpointCategory) {
        counter(
                "fraud_platform_read_access_audit_persistence_failures_total",
                "endpoint_category", normalize(endpointCategory)
        ).increment();
    }

    public void recordAuthenticationFailure(HttpServletRequest request, AuthenticationException exception) {
        counter(
                "fraud.security.auth.failures",
                "auth_type", SecurityFailureClassifier.authType(request),
                "endpoint", endpoint(request),
                "reason", SecurityFailureClassifier.authenticationFailureReason(request, exception)
        ).increment();
    }

    public void recordAccessDenied(HttpServletRequest request, Authentication authentication) {
        counter(
                "fraud.security.access.denied",
                "auth_type", SecurityFailureClassifier.authType(request),
                "endpoint", endpoint(request),
                "reason", SecurityFailureClassifier.accessDeniedReason(authentication),
                "actor_type", SecurityFailureClassifier.actorType(authentication)
        ).increment();
    }

    public void recordActorMismatch(String action) {
        counter(
                "fraud.security.actor.mismatches",
                "action", normalizeAction(action)
        ).increment();
    }

    public void recordGovernanceAdvisoryLifecycle(String lifecycleStatus, String modelName, String modelVersion) {
        counter(
                "fraud_ml_governance_advisory_lifecycle_total",
                "lifecycle_status", normalizeLabel(lifecycleStatus),
                "model_name", normalizeLabel(modelName),
                "model_version", normalizeLabel(modelVersion)
        ).increment();
    }

    public void recordGovernanceAnalyticsRequest(int windowDays) {
        governanceAnalyticsWindowDays.set(windowDays);
        counter("fraud_ml_governance_analytics_requests_total").increment();
    }

    public void recordGovernanceAnalyticsOutcome(String status, Duration latency) {
        counter(
                "fraud_ml_governance_analytics_status_total",
                "status", normalizeAnalyticsStatus(status)
        ).increment();
        Timer.builder("fraud_ml_governance_analytics_latency_seconds")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry)
                .record(latency);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name)
                .tags(tags)
                .register(meterRegistry);
    }

    private String endpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method)
                && path != null
                && path.startsWith("/api/v1/alerts/")
                && path.endsWith("/decision")) {
            return "alerts_decision";
        }
        if (path == null) {
            return "unknown";
        }
        if (path.startsWith("/api/v1/alerts")) {
            return "alerts";
        }
        if (path.startsWith("/api/v1/fraud-cases")) {
            return "fraud_cases";
        }
        if (path.startsWith("/api/v1/transactions/scored")) {
            return "scored_transactions";
        }
        if (path.startsWith("/actuator")) {
            return "actuator";
        }
        return "unknown";
    }

    private String normalize(Enum<?> value) {
        return value.name().toLowerCase();
    }

    private String normalizeAction(String action) {
        if (!StringUtils.hasText(action)) {
            return "unknown";
        }
        return action.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    private String normalizeLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._:-]+", "_");
    }

    private String normalizeAnalyticsStatus(String status) {
        if ("AVAILABLE".equals(status) || "PARTIAL".equals(status) || "UNAVAILABLE".equals(status)) {
            return status;
        }
        return "UNAVAILABLE";
    }

    private String normalizeAvailabilityStatus(String status) {
        if ("AVAILABLE".equals(status) || "UNAVAILABLE".equals(status)) {
            return status;
        }
        return "UNAVAILABLE";
    }
}
