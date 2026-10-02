package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.TransactionalOutboxRuntimeReadiness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RegulatedMutationRecoveryScheduler {

    private final RegulatedMutationRecoveryService recoveryService;
    private final boolean enabled;
    private final TransactionalOutboxRuntimeReadiness runtimeReadiness;

    public RegulatedMutationRecoveryScheduler(
            RegulatedMutationRecoveryService recoveryService,
            @Value("${app.regulated-mutation.recovery.scheduler.enabled:true}") boolean enabled,
            TransactionalOutboxRuntimeReadiness runtimeReadiness
    ) {
        this.recoveryService = recoveryService;
        this.enabled = enabled;
        this.runtimeReadiness = runtimeReadiness;
    }

    @Scheduled(fixedDelayString = "${app.regulated-mutation.recovery.scheduler.interval:PT1M}")
    public void recoverStuckCommands() {
        if (enabled && runtimeReadiness.isReady()) {
            recoveryService.recoverStuckCommands();
        }
    }
}
