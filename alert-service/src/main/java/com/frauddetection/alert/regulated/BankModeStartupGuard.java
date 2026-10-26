package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.runtime.InstitutionalModePolicy;
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
            @Value("${app.audit.external-anchoring.publication.enabled:false}") boolean externalPublicationEnabled,
            @Value("${app.audit.external-anchoring.publication.required:false}") boolean externalPublicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:false}") boolean externalPublicationFailClosed,
            @Value("${app.audit.trust-authority.enabled:false}") boolean trustAuthorityEnabled,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean trustAuthoritySigningRequired,
            @Value("${app.audit.external-anchoring.sink:disabled}") String externalAnchoringSink,
            @Value("${app.trust-incidents.refresh-mode:ATOMIC}") String trustIncidentRefreshMode,
            @Value("${app.outbox.max-attempts:5}") int maxAttempts
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
        if (externalPublicationRequired && externalPublicationFailClosed) {
            if (!bankModeFailClosed) {
                throw new IllegalStateException(
                        "app.audit.bank-mode.fail-closed=true is required when external publication is required and fail-closed."
                );
            }
            log.info("External audit publication fail-closed mode active.");
        }
        boolean prodLike = InstitutionalModePolicy.isInstitutionalMode(environment, bankModeFailClosed);
        if (transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED && transactionCapabilityProbeEnabled) {
            if (transactionCapabilityProbe == null) {
                throw new IllegalStateException("Regulated mutation transaction-mode=REQUIRED requires "
                        + "app.regulated-mutations.transaction-capability-probe.enabled=true with a transaction "
                        + "capability probe.");
            }
            transactionCapabilityProbe.verify();
        }
        if (!prodLike) {
            return;
        }
        require("app.audit.bank-mode.fail-closed", "true", bankModeFailClosed, "institutional profiles require the bank fail-closed contract.");
        require("app.regulated-mutations.transaction-mode", "REQUIRED", transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED, "institutional mode requires regulated mutations to run in transactional REQUIRED mode.");
        require("mongo transaction manager", "present", transactionManager != null, "institutional mode requires a Mongo transaction manager for REQUIRED transactions.");
        require("app.regulated-mutations.transaction-capability-probe.enabled", "true", transactionCapabilityProbeEnabled, "institutional mode must prove transaction capability at startup.");
        require("app.trust-incidents.refresh-mode", "ATOMIC", trustIncidentRefreshMode == TrustIncidentRefreshMode.ATOMIC, "institutional mode requires ATOMIC trust incident refresh.");
        require("app.outbox.publisher.enabled", "true", outboxPublisherEnabled, "institutional mode requires transactional outbox publishing.");
        require("TransactionalOutboxRecordRepository", "present", outboxRepository != null, "institutional mode requires the transactional outbox repository.");
        require("app.outbox.recovery.enabled", "true", outboxRecoveryEnabled, "institutional mode requires explicit outbox recovery.");
        require("app.outbox.confirmation.dual-control.enabled", "true", outboxConfirmationDualControlEnabled, "manual outbox confirmation resolution requires dual control.");
        require("app.sensitive-reads.audit.fail-closed", "true", sensitiveReadAuditFailClosed, "institutional mode requires sensitive operational reads to fail closed.");
        require("app.outbox.max-attempts", ">0", maxAttempts > 0, "outbox retries must be bounded and positive.");
        require("app.audit.external-anchoring.publication.enabled", "true", externalPublicationEnabled, "institutional mode requires external proof publication.");
        require("app.audit.external-anchoring.publication.required", "true", externalPublicationRequired, "institutional mode requires external proof as a hard dependency.");
        require("app.audit.external-anchoring.publication.fail-closed", "true", externalPublicationFailClosed, "institutional mode must fail closed when external proof publication is unavailable.");
        require("app.audit.external-anchoring.sink", "non-local production-capable sink", productionCapableExternalSink(), "institutional mode cannot use disabled/noop/local/same-database external anchor sinks.");
        require("app.audit.trust-authority.enabled", "true", trustAuthorityEnabled, "institutional mode requires signed external evidence.");
        require("app.audit.trust-authority.signing-required", "true", trustAuthoritySigningRequired, "institutional mode requires Trust Authority signing for external evidence.");
        log.info("Institutional mode active: transaction-mode=REQUIRED, trust-incidents.refresh-mode=ATOMIC, "
                + "outbox dual-control and sensitive-read fail-closed enabled.");
    }

    private boolean productionCapableExternalSink() {
        return !externalAnchoringSink.equals("disabled")
                && !externalAnchoringSink.equals("noop")
                && !externalAnchoringSink.equals("local-file")
                && !externalAnchoringSink.equals("in-memory")
                && !externalAnchoringSink.equals("same-database");
    }

    private void require(String setting, String required, boolean valid, String reason) {
        if (!valid) {
            throw new IllegalStateException("Bank/prod startup guard failed: setting="
                    + setting + "; required=" + required + "; reason=" + reason);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "disabled" : value.trim().toLowerCase(Locale.ROOT);
    }
}
