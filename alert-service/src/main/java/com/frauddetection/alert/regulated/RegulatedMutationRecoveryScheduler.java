package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RegulatedMutationRecoveryScheduler {

    private final RegulatedMutationRecoveryService recoveryService;
    private final boolean enabled;

    public RegulatedMutationRecoveryScheduler(
            RegulatedMutationRecoveryService recoveryService,
            @Value("${app.regulated-mutation.recovery.scheduler.enabled:true}") boolean enabled
    ) {
        this.recoveryService = recoveryService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.regulated-mutation.recovery.scheduler.interval:PT1M}")
    public void recoverStuckCommands() {
        if (enabled) {
            recoveryService.recoverStuckCommands();
        }
    }
}
