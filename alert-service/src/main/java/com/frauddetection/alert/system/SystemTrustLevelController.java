package com.frauddetection.alert.system;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalWitnessCapabilities;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.trust.TrustIncidentService;
import com.frauddetection.alert.trust.TrustIncidentSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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
    private final TransactionalOutboxRecordRepository outboxRepository;
    private final RegulatedMutationRecoveryService regulatedMutationRecoveryService;
    private final Duration staleOutboxThreshold;
    private final String transactionMode;
    private final boolean outboxPublisherEnabled;
    private final boolean evidenceConfirmationEnabled;
    private final TrustIncidentService trustIncidentService;
    private final SensitiveReadAuditService sensitiveReadAuditService;

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
                Duration.ofMinutes(10), bankModeFailClosed ? "REQUIRED" : "OFF", true, true, externalAuditIntegrityService, externalAuditAnchorSink, null, null, null, null, null, null, null);
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
            @Value("${app.regulated-mutations.transaction-mode:OFF}") String transactionMode,
            @Value("${app.outbox.publisher.enabled:true}") boolean outboxPublisherEnabled,
            @Value("${app.evidence-confirmation.enabled:true}") boolean evidenceConfirmationEnabled,
            com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AuditDegradationService auditDegradationService,
            AlertRepository alertRepository,
            ObjectProvider<TransactionalOutboxRecordRepository> outboxRepository,
            RegulatedMutationRecoveryService regulatedMutationRecoveryService,
            ObjectProvider<TrustIncidentService> trustIncidentService,
            ObjectProvider<com.frauddetection.alert.trust.TrustSignalCollector> ignoredTrustSignalCollector,
            ObjectProvider<SensitiveReadAuditService> sensitiveReadAuditService
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
        this.outboxRepository = outboxRepository == null ? null : outboxRepository.getIfAvailable();
        this.regulatedMutationRecoveryService = regulatedMutationRecoveryService;
        this.staleOutboxThreshold = staleOutboxThreshold == null ? Duration.ofMinutes(10) : staleOutboxThreshold;
        this.transactionMode = transactionMode == null || transactionMode.isBlank() ? "OFF" : transactionMode.trim().toUpperCase();
        this.outboxPublisherEnabled = outboxPublisherEnabled;
        this.evidenceConfirmationEnabled = evidenceConfirmationEnabled;
        this.trustIncidentService = trustIncidentService == null ? null : trustIncidentService.getIfAvailable();
        this.sensitiveReadAuditService = sensitiveReadAuditService == null ? null : sensitiveReadAuditService.getIfAvailable();
    }

    public SystemTrustLevelController(
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean failClosed,
            boolean bankModeFailClosed,
            boolean trustAuthorityEnabled,
            boolean signingRequired,
            Duration staleOutboxThreshold,
            com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AuditDegradationService auditDegradationService,
            AlertRepository alertRepository
    ) {
        this(
                publicationEnabled,
                publicationRequired,
                failClosed,
                bankModeFailClosed,
                trustAuthorityEnabled,
                signingRequired,
                staleOutboxThreshold,
                externalAuditIntegrityService,
                externalAuditAnchorSink,
                auditDegradationService,
                alertRepository,
                null
        );
    }

    public SystemTrustLevelController(
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean failClosed,
            boolean bankModeFailClosed,
            boolean trustAuthorityEnabled,
            boolean signingRequired,
            Duration staleOutboxThreshold,
            com.frauddetection.alert.audit.external.ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AuditDegradationService auditDegradationService,
            AlertRepository alertRepository,
            RegulatedMutationRecoveryService regulatedMutationRecoveryService
    ) {
        this(
                publicationEnabled,
                publicationRequired,
                failClosed,
                bankModeFailClosed,
                trustAuthorityEnabled,
                signingRequired,
                staleOutboxThreshold,
                bankModeFailClosed ? "REQUIRED" : "OFF",
                true,
                true,
                externalAuditIntegrityService,
                externalAuditAnchorSink,
                auditDegradationService,
                alertRepository,
                null,
                regulatedMutationRecoveryService,
                null,
                null,
                null
        );
    }

    @GetMapping("/system/trust-level")
    @AuditedSensitiveRead
    public SystemTrustLevelResponse trustLevel(HttpServletRequest request) {
        LiveTrustState live = liveTrustState();
        SystemTrustLevelResponse response = new SystemTrustLevelResponse(
                guaranteeLevel(live),
                bankModeFailClosed ? "BANK_PROFILE_ACTIVE" : "NON_BANK_LOCAL_MODE",
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
                live.postCommitAuditDegraded(),
                live.pendingDegradationResolutionCount(),
                live.postCommitAuditDegradedResolved(),
                live.outboxFailedTerminalCount(),
                live.outboxPendingCount(),
                live.outboxProcessingCount(),
                live.outboxPublishAttemptedCount(),
                live.outboxPublishConfirmationUnknownCount(),
                live.outboxProjectionMismatchCount(),
                live.outboxFailedTerminalCount(),
                live.outboxPublishConfirmationUnknownCount(),
                live.outboxPublishConfirmationUnknownCount(),
                live.outboxPendingResolutionCount(),
                live.outboxOldestPendingAgeSeconds(),
                live.outboxOldestAmbiguousAgeSeconds(),
                live.regulatedMutationRecoveryRequiredCount(),
                live.staleProcessingLeaseCount(),
                live.committedDegradedCount(),
                live.evidenceConfirmationPendingCount(),
                live.evidenceConfirmationFailedCount(),
                live.repeatedRecoveryFailureCount(),
                live.oldestRecoveryRequiredAgeSeconds(),
                live.reasonCode(),
                transactionMode,
                transactionCapabilityStatus(),
                outboxDeliveryMode(),
                evidenceConfirmationEnabled ? "ENABLED" : "DISABLED",
                live.openCriticalIncidentCount(),
                live.openHighIncidentCount(),
                live.unacknowledgedCriticalIncidentCount(),
                live.oldestOpenIncidentAgeSeconds(),
                live.topIncidentTypes(),
                live.incidentHealthStatus()
        );
        if (sensitiveReadAuditService != null) {
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.SYSTEM_TRUST_LEVEL,
                    ReadAccessResourceType.SYSTEM_TRUST_LEVEL,
                    null,
                    1,
                    request
            );
        }
        return response;
    }

    public SystemTrustLevelResponse trustLevel() {
        return trustLevel(null);
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
        long pendingDegradationResolution = auditDegradationService == null ? 0L : auditDegradationService.pendingResolutionCount();
        long postCommitDegradedResolved = auditDegradationService == null ? 0L : auditDegradationService.resolvedCount();
        long regulatedRecoveryRequired = regulatedMutationRecoveryService == null ? 0L : regulatedMutationRecoveryService.recoveryRequiredCount();
        long staleProcessingLeaseCount = regulatedMutationRecoveryService == null ? 0L : regulatedMutationRecoveryService.staleProcessingLeaseCount();
        long committedDegradedCount = regulatedMutationRecoveryService == null ? 0L : regulatedMutationRecoveryService.committedDegradedCount();
        long evidenceConfirmationPendingCount = regulatedMutationRecoveryService == null ? 0L : regulatedMutationRecoveryService.evidenceConfirmationPendingCount();
        long evidenceConfirmationFailedCount = regulatedMutationRecoveryService == null ? 0L : regulatedMutationRecoveryService.evidenceConfirmationFailedCount();
        long repeatedRecoveryFailureCount = regulatedMutationRecoveryService == null ? 0L : regulatedMutationRecoveryService.repeatedRecoveryFailureCount();
        Long oldestRecoveryRequiredAgeSeconds = regulatedMutationRecoveryService == null ? null : regulatedMutationRecoveryService.oldestRecoveryRequiredAgeSeconds();
        OutboxState outboxState = outboxState();
        TrustIncidentSummary incidentSummary = trustIncidentSummary();
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
                && pendingDegradationResolution == 0
                && (!bankModeFailClosed || "REQUIRED".equals(transactionMode))
                && outboxState.failedTerminalCount() == 0
                && outboxState.projectionMismatchCount() == 0
                && outboxState.publishConfirmationUnknownCount() == 0
                && outboxState.publishAttemptedCount() == 0
                && outboxState.pendingResolutionCount() == 0
                && !outboxState.stalePending()
                && regulatedRecoveryRequired == 0
                && staleProcessingLeaseCount == 0
                && committedDegradedCount == 0
                && evidenceConfirmationFailedCount == 0
                && repeatedRecoveryFailureCount == 0
                && oldestRecoveryRequiredAgeSeconds == null
                && incidentSummary.openCriticalIncidentCount() == 0
                && incidentSummary.unacknowledgedCriticalIncidentCount() == 0;
        if (healthy && outboxState.reasonCode() != null) {
            healthy = false;
        }
        if (reasonCode == null) {
            reasonCode = outboxState.reasonCode();
        }
        if (reasonCode == null && bankModeFailClosed && !"REQUIRED".equals(transactionMode)) {
            reasonCode = "TRANSACTION_MODE_OFF_IN_BANK_MODE";
        }
        if (reasonCode == null && pendingDegradationResolution > 0) {
            reasonCode = "AUDIT_DEGRADATION_RESOLUTION_PENDING_APPROVAL";
        }
        if (reasonCode == null && regulatedRecoveryRequired > 0) {
            reasonCode = "REGULATED_MUTATION_RECOVERY_REQUIRED";
        }
        if (reasonCode == null && staleProcessingLeaseCount > 0) {
            reasonCode = "REGULATED_MUTATION_STALE_PROCESSING_LEASE";
        }
        if (reasonCode == null && committedDegradedCount > 0) {
            reasonCode = "REGULATED_MUTATION_COMMITTED_DEGRADED";
        }
        if (reasonCode == null && evidenceConfirmationFailedCount > 0) {
            reasonCode = "EVIDENCE_CONFIRMATION_FAILED";
        }
        if (reasonCode == null && repeatedRecoveryFailureCount > 0) {
            reasonCode = "REGULATED_MUTATION_REPEATED_RECOVERY_FAILURE";
        }
        if (reasonCode == null && incidentSummary.unacknowledgedCriticalIncidentCount() > 0) {
            reasonCode = "TRUST_INCIDENT_UNACKNOWLEDGED_CRITICAL";
        }
        if (reasonCode == null && incidentSummary.openCriticalIncidentCount() > 0) {
            reasonCode = "TRUST_INCIDENT_OPEN_CRITICAL";
        }
        return new LiveTrustState(
                healthy,
                coverageStatus,
                witnessStatus,
                requiredFailures,
                localStatusUnverified,
                missingRanges,
                postCommitDegraded,
                pendingDegradationResolution,
                postCommitDegradedResolved,
                outboxState.failedTerminalCount(),
                outboxState.pendingCount(),
                outboxState.processingCount(),
                outboxState.publishAttemptedCount(),
                outboxState.publishConfirmationUnknownCount(),
                outboxState.projectionMismatchCount(),
                outboxState.pendingResolutionCount(),
                outboxState.oldestPendingAgeSeconds(),
                outboxState.oldestAmbiguousAgeSeconds(),
                regulatedRecoveryRequired,
                staleProcessingLeaseCount,
                committedDegradedCount,
                evidenceConfirmationPendingCount,
                evidenceConfirmationFailedCount,
                repeatedRecoveryFailureCount,
                oldestRecoveryRequiredAgeSeconds,
                reasonCode,
                incidentSummary.openCriticalIncidentCount(),
                incidentSummary.openHighIncidentCount(),
                incidentSummary.unacknowledgedCriticalIncidentCount(),
                incidentSummary.oldestOpenIncidentAgeSeconds(),
                incidentSummary.topIncidentTypes(),
                incidentSummary.incidentHealthStatus()
        );
    }

    private TrustIncidentSummary trustIncidentSummary() {
        if (trustIncidentService == null) {
            return TrustIncidentSummary.empty();
        }
        try {
            return trustIncidentService.summary();
        } catch (RuntimeException exception) {
            return new TrustIncidentSummary(1L, 0L, 1L, null, List.of("TRUST_INCIDENT_CONTROL_PLANE_UNAVAILABLE"), "CRITICAL");
        }
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
                return new OutboxState(0L, 0L, 0L, 0L, 0L, 0L, 0L, null, null, false, null);
            }
            if (outboxRepository != null) {
                return transactionalOutboxState();
            }
            List<String> pendingStatuses = List.of(
                    DecisionOutboxStatus.PENDING,
                    DecisionOutboxStatus.PROCESSING,
                    DecisionOutboxStatus.FAILED_RETRYABLE
            );
            long failedTerminalCount = alertRepository.countByDecisionOutboxStatus(DecisionOutboxStatus.FAILED_TERMINAL);
            long unknownCount = alertRepository.countByDecisionOutboxStatus(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
            long pendingResolutionCount = alertRepository.countByDecisionOutboxResolutionPending(true);
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
            } else if (pendingResolutionCount > 0) {
                reason = "OUTBOX_RESOLUTION_PENDING_APPROVAL";
            } else if (stalePending) {
                reason = "OUTBOX_STALE_PENDING";
            }
            return new OutboxState(0L, 0L, 0L, failedTerminalCount, unknownCount, 0L, pendingResolutionCount, oldestPendingAge, oldestUnknownAge, stalePending, reason);
        } catch (DataAccessException exception) {
            return new OutboxState(0L, 0L, 0L, 1L, 1L, 1L, 1L, null, null, true, "OUTBOX_STATUS_UNAVAILABLE");
        }
    }

    private OutboxState transactionalOutboxState() {
        List<TransactionalOutboxStatus> pendingStatuses = List.of(
                TransactionalOutboxStatus.PENDING,
                TransactionalOutboxStatus.PROCESSING,
                TransactionalOutboxStatus.FAILED_RETRYABLE
        );
        long failedTerminalCount = outboxRepository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL);
        long pendingCount = outboxRepository.countByStatus(TransactionalOutboxStatus.PENDING);
        long processingCount = outboxRepository.countByStatus(TransactionalOutboxStatus.PROCESSING);
        long publishAttemptedCount = outboxRepository.countByStatus(TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        long unknownCount = outboxRepository.countByStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        long projectionMismatchCount = outboxRepository.countByProjectionMismatchTrue();
        Long oldestPendingAge = outboxRepository.findTopByStatusInOrderByCreatedAtAsc(pendingStatuses)
                .map(document -> {
                    Instant created = document.getCreatedAt();
                    return created == null ? 0L : Math.max(0L, Duration.between(created, Instant.now()).toSeconds());
                })
                .orElse(null);
        boolean stalePending = oldestPendingAge != null && oldestPendingAge > staleOutboxThreshold.toSeconds();
        String reason = null;
        if (failedTerminalCount > 0) {
            reason = "OUTBOX_TERMINAL_FAILURE";
        } else if (projectionMismatchCount > 0) {
            reason = "OUTBOX_PROJECTION_MISMATCH";
        } else if (publishAttemptedCount > 0) {
            reason = "OUTBOX_PUBLISH_ATTEMPT_CONFIRMATION_PENDING";
        } else if (unknownCount > 0) {
            reason = "OUTBOX_PUBLISH_CONFIRMATION_UNKNOWN";
        } else if (stalePending) {
            reason = "OUTBOX_STALE_PENDING";
        }
        return new OutboxState(pendingCount, processingCount, publishAttemptedCount, failedTerminalCount, unknownCount, projectionMismatchCount, 0L, oldestPendingAge, null, stalePending, reason);
    }

    private String outboxDeliveryMode() {
        return outboxPublisherEnabled ? "TRANSACTIONAL_OUTBOX_AT_LEAST_ONCE" : "DISABLED";
    }

    private String transactionCapabilityStatus() {
        if ("REQUIRED".equals(transactionMode)) {
            return "LOCAL_MONGO_TRANSACTION_REQUIRED";
        }
        return "NON_TRANSACTIONAL_RECOVERABLE_SAGA";
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
            long pendingDegradationResolutionCount,
            long postCommitAuditDegradedResolved,
            long outboxFailedTerminalCount,
            long outboxPendingCount,
            long outboxProcessingCount,
            long outboxPublishAttemptedCount,
            long outboxPublishConfirmationUnknownCount,
            long outboxProjectionMismatchCount,
            long outboxPendingResolutionCount,
            Long outboxOldestPendingAgeSeconds,
            Long outboxOldestAmbiguousAgeSeconds,
            long regulatedMutationRecoveryRequiredCount,
            long staleProcessingLeaseCount,
            long committedDegradedCount,
            long evidenceConfirmationPendingCount,
            long evidenceConfirmationFailedCount,
            long repeatedRecoveryFailureCount,
            Long oldestRecoveryRequiredAgeSeconds,
            String reasonCode,
            long openCriticalIncidentCount,
            long openHighIncidentCount,
            long unacknowledgedCriticalIncidentCount,
            Long oldestOpenIncidentAgeSeconds,
            List<String> topIncidentTypes,
            String incidentHealthStatus
    ) {
    }

    private record OutboxState(
            long pendingCount,
            long processingCount,
            long publishAttemptedCount,
            long failedTerminalCount,
            long publishConfirmationUnknownCount,
            long projectionMismatchCount,
            long pendingResolutionCount,
            Long oldestPendingAgeSeconds,
            Long oldestAmbiguousAgeSeconds,
            boolean stalePending,
            String reasonCode
    ) {
    }
}
