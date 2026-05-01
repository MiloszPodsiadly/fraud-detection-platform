package com.frauddetection.alert.regulated;

import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.mutation.decisionoutbox.DecisionOutboxRecoveryStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RegulatedMutationRecoveryStrategyGuardTest {

    @Test
    void everyRegisteredRegulatedMutationHasRecoveryStrategy() {
        AlertRepository alertRepository = mock(AlertRepository.class);
        List<RegulatedMutationRecoveryStrategy> strategies = List.of(
                new SubmitDecisionRecoveryStrategy(alertRepository),
                new DecisionOutboxRecoveryStrategy(alertRepository)
        );

        for (RegulatedMutationDefinition definition : RegulatedMutationDefinitions.all()) {
            assertThat(strategies)
                    .anySatisfy(strategy -> assertThat(strategy.supports(definition.action(), definition.resourceType())).isTrue());
        }
    }

    @Test
    void bankModeFailsStartupWhenStrategyIsMissing() {
        RegulatedMutationRecoveryStrategyGuard guard = new RegulatedMutationRecoveryStrategyGuard(
                List.of(new SubmitDecisionRecoveryStrategy(mock(AlertRepository.class))),
                true
        );

        assertThatThrownBy(() -> guard.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Regulated mutation recovery strategy missing");
    }
}
