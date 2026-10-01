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

    public TransactionalOutboxPersistedContractStartupGuard(
            TransactionalOutboxPersistedContractPreflight preflight
    ) {
        this.preflight = preflight;
    }

    @Override
    public void run(ApplicationArguments args) {
        TransactionalOutboxPersistedContractPreflight.Report report =
                preflight.inspect(DIAGNOSTIC_SAMPLE_LIMIT);
        if (report.blocksStartup()) {
            throw new IllegalStateException(
                    "Canonical transactional outbox startup blocked by records containing retired resolution_reason: unfinishedCount="
                            + report.unsupportedUnfinishedCount()
                            + "; terminalCount=" + report.unsupportedTerminalCount()
                            + "; samples=" + report.samples()
            );
        }
    }
}
