package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.TransactionalOutboxRuntimeReadiness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MutationEvidenceConfirmationScheduler {

    private final MutationEvidenceConfirmationService service;
    private final boolean enabled;
    private final TransactionalOutboxRuntimeReadiness runtimeReadiness;

    public MutationEvidenceConfirmationScheduler(
            MutationEvidenceConfirmationService service,
            @Value("${app.evidence-confirmation.enabled:true}") boolean enabled,
            TransactionalOutboxRuntimeReadiness runtimeReadiness
    ) {
        this.service = service;
        this.enabled = enabled;
        this.runtimeReadiness = runtimeReadiness;
    }

    @Scheduled(fixedDelayString = "${app.evidence-confirmation.delay-ms:10000}")
    public void confirmPendingEvidence() {
        if (enabled && runtimeReadiness.isReady()) {
            service.confirmPendingEvidence(100);
        }
    }
}
