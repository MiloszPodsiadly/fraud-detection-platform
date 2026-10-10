package com.frauddetection.alert.system.trustlevel.health;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.trust.TrustIncidentService;
import com.frauddetection.alert.trust.TrustIncidentSummary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LiveTrustStateCollector {

    private final boolean publicationEnabled;
    private final ExternalAuditIntegrityService externalAuditIntegrityService;
    private final ExternalAuditAnchorSink externalAuditAnchorSink;
    private final AuditDegradationService auditDegradationService;
    private final OutboxRecoveryHealthCollector outboxRecoveryHealthCollector;
    private final TrustIncidentService trustIncidentService;

    @Autowired
    public LiveTrustStateCollector(
            @Value("${app.audit.external-anchoring.publication.enabled:false}") boolean publicationEnabled,
            ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AuditDegradationService auditDegradationService,
            OutboxRecoveryHealthCollector outboxRecoveryHealthCollector,
            ObjectProvider<TrustIncidentService> trustIncidentService
    ) {
        this(
                publicationEnabled,
                externalAuditIntegrityService,
                externalAuditAnchorSink,
                auditDegradationService,
                outboxRecoveryHealthCollector,
                trustIncidentService == null ? null : trustIncidentService.getIfAvailable()
        );
    }

    public LiveTrustStateCollector(
            boolean publicationEnabled,
            ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AuditDegradationService auditDegradationService,
            OutboxRecoveryHealthCollector outboxRecoveryHealthCollector,
            TrustIncidentService trustIncidentService
    ) {
        this.publicationEnabled = publicationEnabled;
        this.externalAuditIntegrityService = externalAuditIntegrityService;
        this.externalAuditAnchorSink = externalAuditAnchorSink;
        this.auditDegradationService = auditDegradationService;
        this.outboxRecoveryHealthCollector = outboxRecoveryHealthCollector;
        this.trustIncidentService = trustIncidentService;
    }

    public LiveTrustSnapshot collect() {
        long postCommitDegraded = auditDegradationService == null ? 0L : auditDegradationService.unresolvedPostCommitDegradedCount();
        long pendingDegradationResolution = auditDegradationService == null ? 0L : auditDegradationService.pendingResolutionCount();
        long postCommitDegradedResolved = auditDegradationService == null ? 0L : auditDegradationService.resolvedCount();
        OutboxRecoveryHealthSnapshot outboxRecovery = outboxRecoveryHealthCollector.collect();
        TrustIncidentSummary incidentSummary = trustIncidentSummary();

        String coverageStatus = "UNAVAILABLE";
        String reasonCode = null;
        int requiredFailures = 0;
        int localStatusUnverified = 0;
        int missingRanges = 0;
        try {
            ExternalAuditAnchorCoverageResponse coverage = externalAuditIntegrityService.coverage("alert-service", 100);
            coverageStatus = coverage.coverageStatus();
            reasonCode = coverage.reasonCode();
            requiredFailures = coverage.requiredPublicationFailures();
            localStatusUnverified = coverage.localStatusUnverified();
            missingRanges = coverage.missingRanges() == null ? 0 : coverage.missingRanges().size();
            if (!"AVAILABLE".equals(coverage.status())) {
                coverageStatus = "DEGRADED";
                reasonCode = coverage.reasonCode() == null ? "COVERAGE_UNAVAILABLE" : coverage.reasonCode();
            }
        } catch (RuntimeException exception) {
            reasonCode = "COVERAGE_UNAVAILABLE";
        }
        return new LiveTrustSnapshot(
                coverageStatus,
                reasonCode,
                witnessStatus(),
                requiredFailures,
                localStatusUnverified,
                missingRanges,
                postCommitDegraded,
                pendingDegradationResolution,
                postCommitDegradedResolved,
                outboxRecovery,
                incidentSummary
        );
    }

    private TrustIncidentSummary trustIncidentSummary() {
        if (trustIncidentService == null) {
            return TrustIncidentSummary.empty();
        }
        try {
            return trustIncidentService.summary();
        } catch (RuntimeException exception) {
            return new TrustIncidentSummary(
                    1L,
                    0L,
                    1L,
                    null,
                    List.of("TRUST_INCIDENT_CONTROL_PLANE_UNAVAILABLE"),
                    "CRITICAL"
            );
        }
    }

    private String witnessStatus() {
        ExternalWitnessCapabilities capabilities = externalAuditAnchorSink.capabilities();
        if (capabilities == null || "DISABLED".equals(capabilities.witnessType())) {
            return publicationEnabled ? "UNAVAILABLE" : "DISABLED";
        }
        if (capabilities.immutabilityLevel() == ExternalImmutabilityLevel.ENFORCED
                && capabilities.supportsReadAfterWrite()
                && capabilities.supportsStableReference()
                && capabilities.supportsVersioning()
                && capabilities.supportsRetention()
                && capabilities.supportsWriteOnce()
                && capabilities.supportsDeleteDenialOrRetention()) {
            return "PROVIDER_CAPABILITY_VERIFIED";
        }
        return "DECLARED_CAPABLE";
    }
}
