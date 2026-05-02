package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.function.Supplier;

@Component
public class RegulatedMutationTransactionRunner {

    private final RegulatedMutationTransactionMode mode;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public RegulatedMutationTransactionRunner(
            @Value("${app.regulated-mutations.transaction-mode:OFF}") String mode,
            ObjectProvider<PlatformTransactionManager> transactionManager
    ) {
        this.mode = parse(mode);
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        this.transactionTemplate = manager == null ? null : new TransactionTemplate(manager);
    }

    RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode mode, TransactionTemplate transactionTemplate) {
        this.mode = mode == null ? RegulatedMutationTransactionMode.OFF : mode;
        this.transactionTemplate = transactionTemplate;
    }

    public <T> T runLocalCommit(Supplier<T> callback) {
        if (mode == RegulatedMutationTransactionMode.OFF) {
            return callback.get();
        }
        if (transactionTemplate == null) {
            throw new IllegalStateException("FDP-26 transaction-mode=REQUIRED requires a Mongo transaction manager.");
        }
        return transactionTemplate.execute(status -> callback.get());
    }

    public RegulatedMutationTransactionMode mode() {
        return mode;
    }

    private static RegulatedMutationTransactionMode parse(String value) {
        if (value == null || value.isBlank()) {
            return RegulatedMutationTransactionMode.OFF;
        }
        return RegulatedMutationTransactionMode.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
