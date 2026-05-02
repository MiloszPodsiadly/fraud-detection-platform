package com.frauddetection.alert.regulated;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RegulatedMutationRecoveryStrategyGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RegulatedMutationRecoveryStrategyGuard.class);

    private final List<RegulatedMutationRecoveryStrategy> recoveryStrategies;
    private final boolean bankModeFailClosed;

    public RegulatedMutationRecoveryStrategyGuard(
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        this.recoveryStrategies = recoveryStrategies == null ? List.of() : List.copyOf(recoveryStrategies);
        this.bankModeFailClosed = bankModeFailClosed;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<RegulatedMutationDefinition> missing = RegulatedMutationDefinitions.all().stream()
                .filter(RegulatedMutationDefinition::requiresRecoveryStrategy)
                .filter(definition -> recoveryStrategies.stream()
                        .noneMatch(strategy -> strategy.supports(definition.action(), definition.resourceType())))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        if (bankModeFailClosed) {
            throw new IllegalStateException("Regulated mutation recovery strategy missing for " + missing);
        }
        log.warn("Regulated mutation recovery strategy missing for {}.", missing);
    }
}
