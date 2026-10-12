package com.frauddetection.alert.regulated;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

public record RegulatedMutationExecutionContext(String commandId) {

    public void afterDurableCommit(Runnable observation) {
        Objects.requireNonNull(observation, "Commit observation is required.");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            observation.run();
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Active regulated mutation transaction requires synchronization.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    observation.run();
                }
            }
        });
    }
}
