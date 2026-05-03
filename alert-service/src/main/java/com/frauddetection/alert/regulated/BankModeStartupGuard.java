package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.trust.TrustIncidentRefreshMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class BankModeStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BankModeStartupGuard.class);

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
    private final boolean sensitiveReadAuditFailClosed;
    private final boolean externalPublicationEnabled;
    private final boolean externalPublicationRequired;
    private final boolean externalPublicationFailClosed;
    private final boolean trustAuthorityEnabled;
    private final boolean trustAuthoritySigningRequired;
    private final String externalAnchoringSink;
    private final TrustIncidentRefreshMode trustIncidentRefreshMode;
    private final int maxAttempts;

    public BankModeStartupGuard(
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
            @Value("${app.sensitive-reads.audit.fail-closed:false}") boolean sensitiveReadAuditFailClosed,
            @Value("${app.audit.external-anchoring.publication.enabled:${app.audit.external-anchoring.enabled:false}}") boolean externalPublicationEnabled,
            @Value("${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}") boolean externalPublicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}}") boolean externalPublicationFailClosed,
            @Value("${app.audit.trust-authority.enabled:false}") boolean trustAuthorityEnabled,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean trustAuthoritySigningRequired,
            @Value("${app.audit.external-anchoring.sink:disabled}") String externalAnchoringSink,
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
        this.sensitiveReadAuditFailClosed = sensitiveReadAuditFailClosed;
        this.externalPublicationEnabled = externalPublicationEnabled;
        this.externalPublicationRequired = externalPublicationRequired;
        this.externalPublicationFailClosed = externalPublicationFailClosed;
        this.trustAuthorityEnabled = trustAuthorityEnabled;
        this.trustAuthoritySigningRequired = trustAuthoritySigningRequired;
        this.externalAnchoringSink = normalize(externalAnchoringSink);
        this.trustIncidentRefreshMode = TrustIncidentRefreshMode.parse(trustIncidentRefreshMode);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean prodLike = bankModeFailClosed || prodLikeProfile();
        if (transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED && transactionCapabilityProbeEnabled) {
            if (transactionCapabilityProbe == null) {
                throw new IllegalStateException("FDP-27 requires app.regulated-mutations.transaction-capability-probe.enabled=true with a transaction capability probe when transaction-mode=REQUIRED.");
            }
            transactionCapabilityProbe.verify();
        }
        if (!prodLike) {
            return;
        }
        require("app.audit.bank-mode.fail-closed", "true", bankModeFailClosed, "prod/staging/bank profiles must use the bank fail-closed contract.");
        require("app.regulated-mutations.transaction-mode", "REQUIRED", transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED, "regulated mutations must run in transactional REQUIRED mode.");
        require("mongo transaction manager", "present", transactionManager != null, "transaction-mode=REQUIRED must have a Mongo transaction manager.");
        require("app.regulated-mutations.transaction-capability-probe.enabled", "true", transactionCapabilityProbeEnabled, "bank/prod startup must prove transaction capability.");
        require("app.trust-incidents.refresh-mode", "ATOMIC", trustIncidentRefreshMode == TrustIncidentRefreshMode.ATOMIC, "PARTIAL trust incident refresh is local/dev only.");
        require("app.outbox.publisher.enabled", "true", outboxPublisherEnabled, "bank/prod requires transactional outbox publishing.");
        require("TransactionalOutboxRecordRepository", "present", outboxRepository != null, "outbox records are the delivery source of truth.");
        require("app.outbox.recovery.enabled", "true", outboxRecoveryEnabled, "bank/prod requires explicit recovery.");
        require("app.outbox.confirmation.dual-control.enabled", "true", outboxConfirmationDualControlEnabled, "manual outbox confirmation resolution requires dual control.");
        require("app.sensitive-reads.audit.fail-closed", "true", sensitiveReadAuditFailClosed, "sensitive operational reads must fail closed in bank/prod.");
        require("app.outbox.max-attempts", ">0", maxAttempts > 0, "outbox retries must be bounded and positive.");
        if (externalPublicationEnabled || externalPublicationRequired || externalPublicationFailClosed) {
            require("app.audit.external-anchoring.sink", "non-local production-capable sink", productionCapableExternalSink(), "local/noop/in-memory external anchor sinks cannot be used in bank/prod.");
        }
        if (trustAuthorityEnabled) {
            require("app.audit.trust-authority.signing-required", "true", trustAuthoritySigningRequired, "enabled trust authority must require signed evidence in bank/prod.");
        }
        log.info("FDP-27 bank profile active: transaction-mode=REQUIRED, trust-incidents.refresh-mode=ATOMIC, outbox dual-control and sensitive-read fail-closed enabled.");
    }

    private boolean prodLikeProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("staging")
                        || profile.equals("bank"));
    }

    private boolean productionCapableExternalSink() {
        return !externalAnchoringSink.equals("disabled")
                && !externalAnchoringSink.equals("noop")
                && !externalAnchoringSink.equals("local-file")
                && !externalAnchoringSink.equals("in-memory");
    }

    private void require(String setting, String required, boolean valid, String reason) {
        if (!valid) {
            throw new IllegalStateException("FDP-27 bank/prod startup guard failed: setting="
                    + setting + "; required=" + required + "; reason=" + reason);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "disabled" : value.trim().toLowerCase(Locale.ROOT);
    }
}
