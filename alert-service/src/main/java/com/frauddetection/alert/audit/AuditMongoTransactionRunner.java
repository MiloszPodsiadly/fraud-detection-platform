package com.frauddetection.alert.audit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class AuditMongoTransactionRunner {

    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;
    private final boolean transactionManagerRequired;

    @Autowired
    public AuditMongoTransactionRunner(ObjectProvider<PlatformTransactionManager> transactionManager) {
        this(transactionManager == null ? null : transactionManager.getIfAvailable());
    }

    public AuditMongoTransactionRunner(PlatformTransactionManager transactionManager) {
        this.required = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.requiresNew = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (this.requiresNew != null) {
            this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        this.transactionManagerRequired = true;
    }

    AuditMongoTransactionRunner() {
        this.required = null;
        this.requiresNew = null;
        this.transactionManagerRequired = false;
    }

    public <T> T required(Supplier<T> work) {
        if (required == null) {
            if (!transactionManagerRequired) {
                return work.get();
            }
            throw new IllegalStateException("A Mongo transaction manager is required for durable audit append.");
        }
        return required.execute(status -> work.get());
    }

    public <T> T requiresNew(Supplier<T> work) {
        if (requiresNew == null) {
            if (!transactionManagerRequired) {
                return work.get();
            }
            throw new IllegalStateException("A Mongo transaction manager is required for durable audit append.");
        }
        return requiresNew.execute(status -> work.get());
    }

    public void afterCommit(Runnable callback) {
        if (required == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            callback.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                callback.run();
            }
        });
    }

    public void afterCompletion(Runnable callback) {
        if (required == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            callback.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                callback.run();
            }
        });
    }

    public boolean transactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
