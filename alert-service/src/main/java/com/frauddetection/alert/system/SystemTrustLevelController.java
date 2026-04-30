package com.frauddetection.alert.system;

import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemTrustLevelController implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemTrustLevelController.class);

    private final boolean publicationEnabled;
    private final boolean publicationRequired;
    private final boolean failClosed;
    private final boolean trustAuthorityEnabled;
    private final boolean signingRequired;
    private final com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService;
    private final ExternalAuditAnchorSink externalAuditAnchorSink;
    private final AlertServiceMetrics metrics;

    public SystemTrustLevelController(
            @Value("${app.audit.external-anchoring.publication.enabled:${app.audit.external-anchoring.enabled:false}}") boolean publicationEnabled,
            @Value("${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}") boolean publicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}}") boolean failClosed,
            @Value("${app.audit.trust-authority.enabled:false}") boolean trustAuthorityEnabled,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean signingRequired,
            com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AlertServiceMetrics metrics
    ) {
        this.publicationEnabled = publicationEnabled;
        this.publicationRequired = publicationRequired;
        this.failClosed = failClosed;
        this.trustAuthorityEnabled = trustAuthorityEnabled;
        this.signingRequired = signingRequired;
        this.externalAuditIntegrityService = externalAuditIntegrityService;
        this.externalAuditAnchorSink = externalAuditAnchorSink;
        this.metrics = metrics;
    }

    @GetMapping("/system/trust-level")
    public SystemTrustLevelResponse trustLevel() {
        LiveTrustState live = liveTrustState();
        return new SystemTrustLevelResponse(
                guaranteeLevel(live),
                publicationEnabled,
                publicationRequired,
                failClosed,
                externalAnchorStrength(live),
                live.coverageStatus(),
                live.witnessStatus(),
                signaturePolicy(),
                live.requiredPublicationFailures(),
                live.localStatusUnverified(),
                live.missingRanges(),
                live.postCommitAuditDegraded(),
                live.reasonCode()
        );
    }

    @Override
    public void run(ApplicationArguments args) {
        if (publicationRequired && failClosed) {
            log.info("FDP-24 FAIL-CLOSED MODE ACTIVE");
        }
    }

    private String guaranteeLevel(LiveTrustState live) {
        if (!publicationEnabled) {
            return "NONE";
        }
        if (!publicationRequired) {
            return "BEST_EFFORT";
        }
        if (!failClosed) {
            return "FDP24_CONFIGURED";
        }
        return live.healthy() ? "FDP24_HEALTHY" : "FDP24_DEGRADED";
    }

    private String externalAnchorStrength(LiveTrustState live) {
        if (!publicationEnabled || !"HEALTHY".equals(live.coverageStatus())) {
            return "NONE";
        }
        return trustAuthorityEnabled && signingRequired ? "SIGNED_EXTERNAL" : "UNSIGNED_EXTERNAL";
    }

    private String signaturePolicy() {
        if (!trustAuthorityEnabled) {
            return "OPTIONAL";
        }
        return signingRequired ? "REQUIRED_FOR_PUBLICATION" : "REQUIRED_FOR_TRUST";
    }

    private LiveTrustState liveTrustState() {
        ExternalAuditAnchorCoverageResponse coverage = null;
        String coverageStatus = "UNAVAILABLE";
        String reasonCode = null;
        int requiredFailures = 0;
        int localStatusUnverified = 0;
        int missingRanges = 0;
        long postCommitDegraded = metrics.postCommitAuditDegradedCount();
        try {
            coverage = externalAuditIntegrityService.coverage("alert-service", 100);
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
        String witnessStatus = witnessStatus();
        boolean healthy = publicationEnabled
                && publicationRequired
                && failClosed
                && "HEALTHY".equals(coverageStatus)
                && "VERIFIED".equals(witnessStatus)
                && requiredFailures == 0
                && localStatusUnverified == 0
                && missingRanges == 0
                && postCommitDegraded == 0;
        return new LiveTrustState(
                healthy,
                coverageStatus,
                witnessStatus,
                requiredFailures,
                localStatusUnverified,
                missingRanges,
                postCommitDegraded,
                reasonCode
        );
    }

    private String witnessStatus() {
        ExternalWitnessCapabilities capabilities = externalAuditAnchorSink.capabilities();
        if (capabilities == null || "DISABLED".equals(capabilities.witnessType())) {
            return publicationEnabled ? "UNAVAILABLE" : "DISABLED";
        }
        if (capabilities.supportsReadAfterWrite()
                && capabilities.supportsStableReference()
                && capabilities.supportsVersioning()
                && capabilities.supportsRetention()) {
            return "VERIFIED";
        }
        return "DECLARED_ONLY";
    }

    private record LiveTrustState(
            boolean healthy,
            String coverageStatus,
            String witnessStatus,
            int requiredPublicationFailures,
            int localStatusUnverified,
            int missingRanges,
            long postCommitAuditDegraded,
            String reasonCode
    ) {
    }
}
