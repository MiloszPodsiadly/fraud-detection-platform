package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MutationEvidenceConfirmationScheduler {

    private final MutationEvidenceConfirmationService service;
    private final boolean enabled;

    public MutationEvidenceConfirmationScheduler(
            MutationEvidenceConfirmationService service,
            @Value("${app.evidence-confirmation.enabled:true}") boolean enabled
    ) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.evidence-confirmation.delay-ms:10000}")
    public void confirmPendingEvidence() {
        if (enabled) {
            service.confirmPendingEvidence(100);
        }
    }
}
