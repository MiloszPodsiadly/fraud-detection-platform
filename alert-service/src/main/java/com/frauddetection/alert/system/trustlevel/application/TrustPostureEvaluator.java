package com.frauddetection.alert.system.trustlevel.application;

import com.frauddetection.alert.system.trustlevel.health.LiveTrustSnapshot;
import com.frauddetection.alert.system.trustlevel.health.OutboxRecoveryHealthSnapshot;
import com.frauddetection.alert.trust.TrustIncidentSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TrustPostureEvaluator {

    private final boolean publicationEnabled;
    private final boolean publicationRequired;
    private final boolean failClosed;
    private final boolean bankModeFailClosed;
    private final boolean trustAuthorityEnabled;
    private final boolean signingRequired;
    private final String transactionMode;
    private final boolean outboxPublisherEnabled;
    private final boolean evidenceConfirmationEnabled;

    public TrustPostureEvaluator(
            @Value("${app.audit.external-anchoring.publication.enabled:false}") boolean publicationEnabled,
            @Value("${app.audit.external-anchoring.publication.required:false}") boolean publicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:false}") boolean failClosed,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.audit.trust-authority.enabled:false}") boolean trustAuthorityEnabled,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean signingRequired,
            @Value("${app.regulated-mutations.transaction-mode:OFF}") String transactionMode,
            @Value("${app.outbox.publisher.enabled:true}") boolean outboxPublisherEnabled,
            @Value("${app.evidence-confirmation.enabled:true}") boolean evidenceConfirmationEnabled
    ) {
        this.publicationEnabled = publicationEnabled;
        this.publicationRequired = publicationRequired;
        this.failClosed = failClosed;
        this.bankModeFailClosed = bankModeFailClosed;
        this.trustAuthorityEnabled = trustAuthorityEnabled;
        this.signingRequired = signingRequired;
        this.transactionMode = transactionMode == null || transactionMode.isBlank()
                ? "OFF"
                : transactionMode.trim().toUpperCase();
        this.outboxPublisherEnabled = outboxPublisherEnabled;
        this.evidenceConfirmationEnabled = evidenceConfirmationEnabled;
    }

    public SystemTrustLevel evaluate(LiveTrustSnapshot live) {
        OutboxRecoveryHealthSnapshot health = live.outboxRecovery();
        TrustIncidentSummary incidents = live.incidentSummary();
        boolean healthy = isHealthy(live, health, incidents);
        return new SystemTrustLevel(
                guaranteeLevel(healthy),
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
                health.outboxFailedTerminalCount(),
                health.outboxPendingCount(),
                health.outboxProcessingCount(),
                health.outboxPublishAttemptedCount(),
                health.outboxPublishConfirmationUnknownCount(),
                health.outboxProjectionMismatchCount(),
                health.outboxProjectionReconciliationPendingCount(),
                health.outboxRecoveryRequiredCount(),
                health.outboxFailedTerminalCount(),
                health.outboxPublishConfirmationUnknownCount(),
                health.outboxPublishConfirmationUnknownCount(),
                health.outboxPendingResolutionCount(),
                health.outboxOldestPendingAgeSeconds(),
                health.outboxOldestAmbiguousAgeSeconds(),
                health.regulatedMutationRecoveryRequiredCount(),
                health.staleProcessingLeaseCount(),
                health.finalizeRecoveryRequiredCount(),
                health.evidenceConfirmationPendingCount(),
                health.repeatedRecoveryFailureCount(),
                health.oldestRecoveryRequiredAgeSeconds(),
                reasonCode(live, health, incidents),
                transactionMode,
                transactionCapabilityStatus(),
                outboxPublisherEnabled ? "TRANSACTIONAL_OUTBOX_AT_LEAST_ONCE" : "DISABLED",
                evidenceConfirmationEnabled ? "ENABLED_PROVENANCE_AWARE" : "DISABLED",
                incidents.openCriticalIncidentCount(),
                incidents.openHighIncidentCount(),
                incidents.unacknowledgedCriticalIncidentCount(),
                incidents.oldestOpenIncidentAgeSeconds(),
                incidents.topIncidentTypes(),
                incidents.incidentHealthStatus()
        );
    }

    private boolean isHealthy(
            LiveTrustSnapshot live,
            OutboxRecoveryHealthSnapshot health,
            TrustIncidentSummary incidents
    ) {
        boolean healthy = publicationEnabled
                && publicationRequired
                && failClosed
                && "HEALTHY".equals(live.coverageStatus())
                && "PROVIDER_CAPABILITY_VERIFIED".equals(live.witnessStatus())
                && live.requiredPublicationFailures() == 0
                && live.localStatusUnverified() == 0
                && live.missingRanges() == 0
                && live.postCommitAuditDegraded() == 0
                && live.pendingDegradationResolutionCount() == 0
                && (!bankModeFailClosed || (publicationEnabled && publicationRequired && failClosed))
                && (!bankModeFailClosed || (trustAuthorityEnabled && signingRequired))
                && (!bankModeFailClosed || "REQUIRED".equals(transactionMode))
                && health.outboxFailedTerminalCount() == 0
                && health.outboxRecoveryRequiredCount() == 0
                && health.outboxProjectionMismatchCount() == 0
                && health.outboxProjectionReconciliationPendingCount() == 0
                && health.outboxPublishConfirmationUnknownCount() == 0
                && health.outboxPublishAttemptedCount() == 0
                && health.outboxPendingResolutionCount() == 0
                && !health.outboxStalePending()
                && health.outboxAvailable()
                && health.regulatedMutationRecoveryRequiredCount() == 0
                && health.staleProcessingLeaseCount() == 0
                && health.finalizeRecoveryRequiredCount() == 0
                && health.repeatedRecoveryFailureCount() == 0
                && health.oldestRecoveryRequiredAgeSeconds() == null
                && incidents.openCriticalIncidentCount() == 0
                && incidents.unacknowledgedCriticalIncidentCount() == 0;
        return healthy && health.outboxReasonCode() == null;
    }

    private String reasonCode(
            LiveTrustSnapshot live,
            OutboxRecoveryHealthSnapshot health,
            TrustIncidentSummary incidents
    ) {
        String reasonCode = live.coverageReasonCode();
        if (reasonCode == null) {
            reasonCode = health.outboxReasonCode();
        }
        if (reasonCode == null && bankModeFailClosed && !(publicationEnabled && publicationRequired && failClosed)) {
            reasonCode = "EXTERNAL_ANCHORING_REQUIRED_IN_BANK_MODE";
        }
        if (reasonCode == null && bankModeFailClosed && !(trustAuthorityEnabled && signingRequired)) {
            reasonCode = "TRUST_AUTHORITY_SIGNING_REQUIRED_IN_BANK_MODE";
        }
        if (reasonCode == null && bankModeFailClosed && !"REQUIRED".equals(transactionMode)) {
            reasonCode = "TRANSACTION_MODE_OFF_IN_BANK_MODE";
        }
        if (reasonCode == null && live.requiredPublicationFailures() > 0) {
            reasonCode = "EXTERNAL_PUBLICATION_REQUIRED_FAILURE";
        }
        if (reasonCode == null && live.localStatusUnverified() > 0) {
            reasonCode = "EXTERNAL_ANCHOR_LOCAL_STATUS_UNVERIFIED";
        }
        if (reasonCode == null && live.missingRanges() > 0) {
            reasonCode = "EXTERNAL_ANCHOR_MISSING_RANGE";
        }
        if (reasonCode == null && live.pendingDegradationResolutionCount() > 0) {
            reasonCode = "AUDIT_DEGRADATION_RESOLUTION_PENDING_APPROVAL";
        }
        if (reasonCode == null && health.regulatedMutationRecoveryRequiredCount() > 0) {
            reasonCode = "REGULATED_MUTATION_RECOVERY_REQUIRED";
        }
        if (reasonCode == null && health.staleProcessingLeaseCount() > 0) {
            reasonCode = "REGULATED_MUTATION_STALE_PROCESSING_LEASE";
        }
        if (reasonCode == null && health.finalizeRecoveryRequiredCount() > 0) {
            reasonCode = "REGULATED_MUTATION_FINALIZE_RECOVERY_REQUIRED";
        }
        if (reasonCode == null && health.repeatedRecoveryFailureCount() > 0) {
            reasonCode = "REGULATED_MUTATION_REPEATED_RECOVERY_FAILURE";
        }
        if (reasonCode == null && incidents.unacknowledgedCriticalIncidentCount() > 0) {
            reasonCode = "TRUST_INCIDENT_UNACKNOWLEDGED_CRITICAL";
        }
        if (reasonCode == null && incidents.openCriticalIncidentCount() > 0) {
            reasonCode = "TRUST_INCIDENT_OPEN_CRITICAL";
        }
        return reasonCode;
    }

    private String guaranteeLevel(boolean healthy) {
        if (!publicationEnabled) {
            return "NONE";
        }
        if (!publicationRequired) {
            return "BEST_EFFORT";
        }
        if (!failClosed) {
            return "FDP24_CONFIGURED";
        }
        return healthy ? "FDP24_HEALTHY" : "FDP24_DEGRADED";
    }

    private String externalAnchorStrength(LiveTrustSnapshot live) {
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

    private String transactionCapabilityStatus() {
        return "REQUIRED".equals(transactionMode)
                ? "LOCAL_MONGO_TRANSACTION_REQUIRED"
                : "NON_TRANSACTIONAL_RECOVERABLE_SAGA";
    }
}
