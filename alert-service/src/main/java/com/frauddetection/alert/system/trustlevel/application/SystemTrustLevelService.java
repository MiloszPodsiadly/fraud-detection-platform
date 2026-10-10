package com.frauddetection.alert.system.trustlevel.application;

import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.regulated.RegulatedMutationRecoveryService;
import com.frauddetection.alert.system.trustlevel.health.LiveTrustStateCollector;
import com.frauddetection.alert.system.trustlevel.health.OutboxRecoveryHealthCollector;
import com.frauddetection.alert.trust.TrustIncidentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class SystemTrustLevelService {

    private final LiveTrustStateCollector liveTrustStateCollector;
    private final TrustPostureEvaluator trustPostureEvaluator;

    @Autowired
    public SystemTrustLevelService(
            LiveTrustStateCollector liveTrustStateCollector,
            TrustPostureEvaluator trustPostureEvaluator
    ) {
        this.liveTrustStateCollector = liveTrustStateCollector;
        this.trustPostureEvaluator = trustPostureEvaluator;
    }

    public SystemTrustLevelService(
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean failClosed,
            boolean bankModeFailClosed,
            boolean trustAuthorityEnabled,
            boolean signingRequired,
            Duration staleOutboxThreshold,
            String transactionMode,
            boolean outboxPublisherEnabled,
            boolean evidenceConfirmationEnabled,
            ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AuditDegradationService auditDegradationService,
            TransactionalOutboxRecordRepository outboxRepository,
            RegulatedMutationRecoveryService regulatedMutationRecoveryService,
            TrustIncidentService trustIncidentService
    ) {
        this(
                new LiveTrustStateCollector(
                        publicationEnabled,
                        externalAuditIntegrityService,
                        externalAuditAnchorSink,
                        auditDegradationService,
                        new OutboxRecoveryHealthCollector(
                                outboxRepository,
                                regulatedMutationRecoveryService,
                                staleOutboxThreshold
                        ),
                        trustIncidentService
                ),
                new TrustPostureEvaluator(
                        publicationEnabled,
                        publicationRequired,
                        failClosed,
                        bankModeFailClosed,
                        trustAuthorityEnabled,
                        signingRequired,
                        transactionMode,
                        outboxPublisherEnabled,
                        evidenceConfirmationEnabled
                )
        );
    }

    public SystemTrustLevelService(
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean failClosed,
            boolean trustAuthorityEnabled,
            boolean signingRequired,
            ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink
    ) {
        this(
                publicationEnabled,
                publicationRequired,
                failClosed,
                true,
                trustAuthorityEnabled,
                signingRequired,
                Duration.ofMinutes(10),
                "REQUIRED",
                true,
                true,
                externalAuditIntegrityService,
                externalAuditAnchorSink,
                null,
                null,
                null,
                null
        );
    }

    public SystemTrustLevelService(
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean failClosed,
            boolean bankModeFailClosed,
            boolean trustAuthorityEnabled,
            boolean signingRequired,
            Duration staleOutboxThreshold,
            ExternalAuditIntegrityService externalAuditIntegrityService,
            ExternalAuditAnchorSink externalAuditAnchorSink,
            AuditDegradationService auditDegradationService,
            TransactionalOutboxRecordRepository outboxRepository,
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
                outboxRepository,
                regulatedMutationRecoveryService,
                null
        );
    }

    public SystemTrustLevel trustLevel() {
        return trustPostureEvaluator.evaluate(liveTrustStateCollector.collect());
    }
}
