package com.frauddetection.alert.system;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
public class SystemTrustLevelController implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemTrustLevelController.class);

    private final boolean publicationEnabled;
    private final boolean publicationRequired;
    private final boolean failClosed;
    private final boolean bankModeFailClosed;
    private final boolean trustAuthorityEnabled;
    private final boolean signingRequired;
    private final com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService;
    private final ExternalAuditAnchorSink externalAuditAnchorSink;
    private final AuditDegradationService auditDegradationService;
    private final AlertRepository alertRepository;
    private final Duration staleOutboxThreshold;

    public SystemTrustLevelController(
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean failClosed,
            boolean trustAuthorityEnabled,
            boolean signingRequired,
            com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AlertServiceMetrics ignoredMetrics
    ) {
        this(publicationEnabled, publicationRequired, failClosed, true, trustAuthorityEnabled, signingRequired,
                externalAuditIntegrityService, externalAuditAnchorSink, ignoredMetrics);
    }

    public SystemTrustLevelController(
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean failClosed,
            boolean bankModeFailClosed,
            boolean trustAuthorityEnabled,
            boolean signingRequired,
            com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AlertServiceMetrics ignoredMetrics
    ) {
        this(publicationEnabled, publicationRequired, failClosed, bankModeFailClosed, trustAuthorityEnabled, signingRequired,
                Duration.ofMinutes(10), externalAuditIntegrityService, externalAuditAnchorSink, null, null);
    }

    @Autowired
    public SystemTrustLevelController(
            @Value("${app.audit.external-anchoring.publication.enabled:${app.audit.external-anchoring.enabled:false}}") boolean publicationEnabled,
            @Value("${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}") boolean publicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}}") boolean failClosed,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.audit.trust-authority.enabled:false}") boolean trustAuthorityEnabled,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean signingRequired,
            @Value("${app.alert.decision-outbox.stale-threshold:PT10M}") Duration staleOutboxThreshold,
            com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AuditDegradationService auditDegradationService,
            AlertRepository alertRepository
    ) {
        this.publicationEnabled = publicationEnabled;
        this.publicationRequired = publicationRequired;
        this.failClosed = failClosed;
        this.bankModeFailClosed = bankModeFailClosed;
        this.trustAuthorityEnabled = trustAuthorityEnabled;
        this.signingRequired = signingRequired;
        this.externalAuditIntegrityService = externalAuditIntegrityService;
        this.externalAuditAnchorSink = externalAuditAnchorSink;
        this.auditDegradationService = auditDegradationService;
        this.alertRepository = alertRepository;
        this.staleOutboxThreshold = staleOutboxThreshold == null ? Duration.ofMinutes(10) : staleOutboxThreshold;
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
                live.postCommitAuditDegradedResolved(),
                live.outboxFailedTerminalCount(),
                live.outboxPublishConfirmationUnknownCount(),
                live.outboxOldestPendingAgeSeconds(),
                live.outboxOldestAmbiguousAgeSeconds(),
                live.reasonCode()
        );
    }

    @Override
    public void run(ApplicationArguments args) {
        if (publicationRequired && failClosed) {
            if (!bankModeFailClosed) {
                throw new IllegalStateException("app.audit.bank-mode.fail-closed=true is required when external publication is required and fail-closed.");
            }
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
        long postCommitDegraded = auditDegradationService == null ? 0L : auditDegradationService.unresolvedPostCommitDegradedCount();
        long postCommitDegradedResolved = auditDegradationService == null ? 0L : auditDegradationService.resolvedCount();
        OutboxState outboxState = outboxState();
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
                && "PROVIDER_CAPABILITY_VERIFIED".equals(witnessStatus)
                && requiredFailures == 0
                && localStatusUnverified == 0
                && missingRanges == 0
                && postCommitDegraded == 0
                && outboxState.failedTerminalCount() == 0
                && outboxState.publishConfirmationUnknownCount() == 0
                && !outboxState.stalePending();
        if (healthy && outboxState.reasonCode() != null) {
            healthy = false;
        }
        if (reasonCode == null) {
            reasonCode = outboxState.reasonCode();
        }
        return new LiveTrustState(
                healthy,
                coverageStatus,
                witnessStatus,
                requiredFailures,
                localStatusUnverified,
                missingRanges,
                postCommitDegraded,
                postCommitDegradedResolved,
                outboxState.failedTerminalCount(),
                outboxState.publishConfirmationUnknownCount(),
                outboxState.oldestPendingAgeSeconds(),
                outboxState.oldestAmbiguousAgeSeconds(),
                reasonCode
        );
    }

    private String witnessStatus() {
        ExternalWitnessCapabilities capabilities = externalAuditAnchorSink.capabilities();
        if (capabilities == null || "DISABLED".equals(capabilities.witnessType())) {
            return publicationEnabled ? "UNAVAILABLE" : "DISABLED";
        }
        if (capabilities.immutabilityLevel() == com.frauddetection.alert.audit.external.ExternalImmutabilityLevel.ENFORCED
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

    private OutboxState outboxState() {
        try {
            if (alertRepository == null) {
                return new OutboxState(0L, 0L, null, null, false, null);
            }
            List<String> pendingStatuses = List.of(
                    DecisionOutboxStatus.PENDING,
                    DecisionOutboxStatus.PROCESSING,
                    DecisionOutboxStatus.FAILED_RETRYABLE
            );
            long failedTerminalCount = alertRepository.countByDecisionOutboxStatus(DecisionOutboxStatus.FAILED_TERMINAL);
            long unknownCount = alertRepository.countByDecisionOutboxStatus(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
            Long oldestPendingAge = alertRepository.findTopByDecisionOutboxStatusInOrderByDecidedAtAsc(pendingStatuses)
                    .map(this::pendingAgeSeconds)
                    .orElse(null);
            Long oldestUnknownAge = alertRepository.findTopByDecisionOutboxStatusOrderByDecidedAtAsc(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN)
                    .map(this::pendingAgeSeconds)
                    .orElse(null);
            boolean stalePending = oldestPendingAge != null && oldestPendingAge > staleOutboxThreshold.toSeconds();
            String reason = null;
            if (failedTerminalCount > 0) {
                reason = "OUTBOX_TERMINAL_FAILURE";
            } else if (unknownCount > 0) {
                reason = "OUTBOX_PUBLISH_CONFIRMATION_UNKNOWN";
            } else if (stalePending) {
                reason = "OUTBOX_STALE_PENDING";
            }
            return new OutboxState(failedTerminalCount, unknownCount, oldestPendingAge, oldestUnknownAge, stalePending, reason);
        } catch (DataAccessException exception) {
            return new OutboxState(1L, 1L, null, null, true, "OUTBOX_STATUS_UNAVAILABLE");
        }
    }

    private long pendingAgeSeconds(AlertDocument document) {
        Instant decidedAt = document.getDecidedAt();
        if (decidedAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(decidedAt, Instant.now()).toSeconds());
    }

    private record LiveTrustState(
            boolean healthy,
            String coverageStatus,
            String witnessStatus,
            int requiredPublicationFailures,
            int localStatusUnverified,
            int missingRanges,
            long postCommitAuditDegraded,
            long postCommitAuditDegradedResolved,
            long outboxFailedTerminalCount,
            long outboxPublishConfirmationUnknownCount,
            Long outboxOldestPendingAgeSeconds,
            Long outboxOldestAmbiguousAgeSeconds,
            String reasonCode
    ) {
    }

    private record OutboxState(
            long failedTerminalCount,
            long publishConfirmationUnknownCount,
            Long oldestPendingAgeSeconds,
            Long oldestAmbiguousAgeSeconds,
            boolean stalePending,
            String reasonCode
    ) {
    }
}
