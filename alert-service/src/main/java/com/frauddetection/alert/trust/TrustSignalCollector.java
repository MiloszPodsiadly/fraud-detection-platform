package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorCoverageResponse;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TrustSignalCollector {

    private final TransactionalOutboxRecordRepository outboxRepository;
    private final RegulatedMutationRecoveryService recoveryService;
    private final AuditDegradationService auditDegradationService;
    private final ExternalAuditIntegrityService externalAuditIntegrityService;

    public TrustSignalCollector(
            ObjectProvider<TransactionalOutboxRecordRepository> outboxRepository,
            ObjectProvider<RegulatedMutationRecoveryService> recoveryService,
            ObjectProvider<AuditDegradationService> auditDegradationService,
            ObjectProvider<ExternalAuditIntegrityService> externalAuditIntegrityService
    ) {
        this.outboxRepository = outboxRepository.getIfAvailable();
        this.recoveryService = recoveryService.getIfAvailable();
        this.auditDegradationService = auditDegradationService.getIfAvailable();
        this.externalAuditIntegrityService = externalAuditIntegrityService.getIfAvailable();
    }

    public List<TrustSignal> collect() {
        List<TrustSignal> signals = new ArrayList<>();
        collectOutbox(signals);
        collectRegulatedMutation(signals);
        collectAuditDegradation(signals);
        collectCoverage(signals);
        return List.copyOf(signals);
    }

    private void collectOutbox(List<TrustSignal> signals) {
        if (outboxRepository == null) {
            return;
        }
        if (outboxRepository.countByStatus(TransactionalOutboxStatus.FAILED_TERMINAL) > 0) {
            signals.add(signal("OUTBOX_TERMINAL_FAILURE", "transactional_outbox", "status=FAILED_TERMINAL"));
        }
        if (outboxRepository.countByStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN) > 0) {
            signals.add(signal("OUTBOX_PUBLISH_CONFIRMATION_UNKNOWN", "transactional_outbox", "status=PUBLISH_CONFIRMATION_UNKNOWN"));
        }
        if (outboxRepository.countByProjectionMismatchTrue() > 0) {
            signals.add(signal("OUTBOX_PROJECTION_MISMATCH", "transactional_outbox", "projection_mismatch=true"));
        }
    }

    private void collectRegulatedMutation(List<TrustSignal> signals) {
        if (recoveryService == null) {
            return;
        }
        if (recoveryService.recoveryRequiredCount() > 0) {
            signals.add(signal("REGULATED_MUTATION_RECOVERY_REQUIRED", "regulated_mutation", "execution_status=RECOVERY_REQUIRED"));
        }
        if (recoveryService.committedDegradedCount() > 0) {
            signals.add(signal("REGULATED_MUTATION_COMMITTED_DEGRADED", "regulated_mutation", "state=COMMITTED_DEGRADED"));
        }
        if (recoveryService.evidenceConfirmationFailedCount() > 0) {
            signals.add(signal("EVIDENCE_CONFIRMATION_FAILED", "regulated_mutation", "evidence_confirmation=FAILED"));
        }
    }

    private void collectAuditDegradation(List<TrustSignal> signals) {
        if (auditDegradationService != null && auditDegradationService.unresolvedPostCommitDegradedCount() > 0) {
            signals.add(signal("AUDIT_DEGRADATION_UNRESOLVED", "audit_degradation", "status=UNRESOLVED"));
        }
    }

    private void collectCoverage(List<TrustSignal> signals) {
        if (externalAuditIntegrityService == null) {
            return;
        }
        try {
            ExternalAuditAnchorCoverageResponse coverage = externalAuditIntegrityService.coverage("alert-service", 100);
            if (coverage == null || !"AVAILABLE".equals(coverage.status())) {
                signals.add(signal("COVERAGE_UNAVAILABLE", "external_audit_anchor", "coverage=UNAVAILABLE"));
            } else if (coverage.missingRanges() != null && !coverage.missingRanges().isEmpty()) {
                signals.add(signal("EXTERNAL_ANCHOR_GAP", "external_audit_anchor", "missing_ranges=true"));
            }
        } catch (RuntimeException exception) {
            signals.add(signal("COVERAGE_UNAVAILABLE", "external_audit_anchor", "coverage=UNAVAILABLE"));
        }
    }

    private TrustSignal signal(String type, String source, String fingerprint) {
        return new TrustSignal(type, null, source, fingerprint, List.of(source + ":" + type));
    }
}
