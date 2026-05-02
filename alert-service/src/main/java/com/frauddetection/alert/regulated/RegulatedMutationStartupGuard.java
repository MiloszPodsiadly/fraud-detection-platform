package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.trust.TrustIncidentRefreshMode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Arrays;
import java.util.Locale;

@Component
public class RegulatedMutationStartupGuard implements ApplicationRunner {

    private final RegulatedMutationTransactionRunner transactionRunner;
    private final PlatformTransactionManager transactionManager;
    private final TransactionalOutboxRecordRepository outboxRepository;
    private final RegulatedMutationTransactionCapabilityProbe transactionCapabilityProbe;
    private final Environment environment;
    private final boolean bankModeFailClosed;
    private final boolean outboxPublisherEnabled;
    private final boolean outboxRecoveryEnabled;
    private final boolean outboxConfirmationDualControlEnabled;
    private final boolean transactionCapabilityProbeEnabled;
    private final TrustIncidentRefreshMode trustIncidentRefreshMode;
    private final int maxAttempts;

    public RegulatedMutationStartupGuard(
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<TransactionalOutboxRecordRepository> outboxRepository,
            ObjectProvider<RegulatedMutationTransactionCapabilityProbe> transactionCapabilityProbe,
            Environment environment,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.outbox.publisher.enabled:true}") boolean outboxPublisherEnabled,
            @Value("${app.outbox.recovery.enabled:true}") boolean outboxRecoveryEnabled,
            @Value("${app.outbox.confirmation.dual-control.enabled:false}") boolean outboxConfirmationDualControlEnabled,
            @Value("${app.regulated-mutations.transaction-capability-probe.enabled:true}") boolean transactionCapabilityProbeEnabled,
            @Value("${app.trust-incidents.refresh-mode:ATOMIC}") String trustIncidentRefreshMode,
            @Value("${app.outbox.max-attempts:${app.alert.decision-outbox.max-attempts:5}}") int maxAttempts
    ) {
        this.transactionRunner = transactionRunner;
        this.transactionManager = transactionManager.getIfAvailable();
        this.outboxRepository = outboxRepository.getIfAvailable();
        this.transactionCapabilityProbe = transactionCapabilityProbe.getIfAvailable();
        this.environment = environment;
        this.bankModeFailClosed = bankModeFailClosed;
        this.outboxPublisherEnabled = outboxPublisherEnabled;
        this.outboxRecoveryEnabled = outboxRecoveryEnabled;
        this.outboxConfirmationDualControlEnabled = outboxConfirmationDualControlEnabled;
        this.transactionCapabilityProbeEnabled = transactionCapabilityProbeEnabled;
        this.trustIncidentRefreshMode = TrustIncidentRefreshMode.parse(trustIncidentRefreshMode);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean prodLike = bankModeFailClosed || Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production") || profile.equals("staging") || profile.equals("bank"));
        if (transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED && transactionCapabilityProbeEnabled) {
            if (transactionCapabilityProbe == null) {
                throw new IllegalStateException("FDP-26 transaction-mode=REQUIRED requires a transaction capability probe.");
            }
            transactionCapabilityProbe.verify();
        }
        if (!prodLike) {
            return;
        }
        if (transactionRunner.mode() != RegulatedMutationTransactionMode.REQUIRED) {
            throw new IllegalStateException("FDP-26 prod-like/bank mode requires app.regulated-mutations.transaction-mode=REQUIRED.");
        }
        if (transactionManager == null) {
            throw new IllegalStateException("FDP-26 transaction-mode=REQUIRED requires a Mongo transaction manager.");
        }
        if (!transactionCapabilityProbeEnabled) {
            throw new IllegalStateException("FDP-26 prod-like/bank mode requires transaction capability probe enabled.");
        }
        if (trustIncidentRefreshMode != TrustIncidentRefreshMode.ATOMIC) {
            throw new IllegalStateException("FDP-26 prod-like/bank mode requires app.trust-incidents.refresh-mode=ATOMIC.");
        }
        if (!outboxPublisherEnabled) {
            throw new IllegalStateException("FDP-26 prod-like/bank mode requires outbox publisher enabled.");
        }
        if (outboxRepository == null) {
            throw new IllegalStateException("FDP-26 prod-like/bank mode requires TransactionalOutboxRecordRepository.");
        }
        if (!outboxRecoveryEnabled) {
            throw new IllegalStateException("FDP-26 prod-like/bank mode requires outbox recovery enabled.");
        }
        if (!outboxConfirmationDualControlEnabled) {
            throw new IllegalStateException("FDP-26 bank mode requires outbox confirmation dual-control enabled.");
        }
        if (maxAttempts <= 0) {
            throw new IllegalStateException("FDP-26 prod-like/bank mode requires positive outbox max attempts.");
        }
    }
}
