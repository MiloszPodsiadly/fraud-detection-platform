package com.frauddetection.alert.regulated;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegulatedMutationPersistedModelStartupGuard implements ApplicationRunner {

    private static final int DIAGNOSTIC_SAMPLE_LIMIT = 25;

    private final RegulatedMutationPersistedModelPreflight preflight;

    public RegulatedMutationPersistedModelStartupGuard(RegulatedMutationPersistedModelPreflight preflight) {
        this.preflight = preflight;
    }

    @Override
    public void run(ApplicationArguments args) {
        RegulatedMutationPersistedModelPreflight.Report report = preflight.inspect(DIAGNOSTIC_SAMPLE_LIMIT);
        if (report.blocksStartup()) {
            throw new IllegalStateException(
                    "Canonical regulated mutation startup blocked by unsupported persisted commands in the active collection: unfinishedCount="
                            + report.unsupportedUnfinishedCount()
                            + "; terminalCount=" + report.unsupportedTerminalCount()
                            + "; samples=" + report.samples()
            );
        }
    }
}
