package com.frauddetection.alert.outbox;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionalOutboxPersistedContractStartupGuard implements ApplicationRunner {

    private static final int DIAGNOSTIC_SAMPLE_LIMIT = 25;

    private final TransactionalOutboxPersistedContractPreflight preflight;
    private final TransactionalOutboxRuntimeReadiness runtimeReadiness;

    public TransactionalOutboxPersistedContractStartupGuard(
            TransactionalOutboxPersistedContractPreflight preflight,
            TransactionalOutboxRuntimeReadiness runtimeReadiness
    ) {
        this.preflight = preflight;
        this.runtimeReadiness = runtimeReadiness;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            TransactionalOutboxPersistedContractPreflight.Report report =
                    preflight.inspect(DIAGNOSTIC_SAMPLE_LIMIT);
            if (report.blocksStartup()) {
                throw new IllegalStateException(
                        "Canonical transactional outbox startup blocked by unsupported persisted records: unfinishedCount="
                                + report.unsupportedUnfinishedCount()
                                + "; terminalCount=" + report.unsupportedTerminalCount()
                                + "; alertProjectionCount=" + report.unsupportedAlertProjectionCount()
                                + "; samples=" + report.samples()
                );
            }
            runtimeReadiness.markPreflightPassed();
        } catch (RuntimeException exception) {
            runtimeReadiness.markFailed();
            throw exception;
        }
    }
}
