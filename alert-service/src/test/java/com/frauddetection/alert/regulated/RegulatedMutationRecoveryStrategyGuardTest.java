package com.frauddetection.alert.regulated;

import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.regulated.mutation.decisionoutbox.DecisionOutboxRecoveryStrategy;
import com.frauddetection.alert.regulated.mutation.trustincident.TrustIncidentRecoveryStrategy;
import com.frauddetection.alert.trust.TrustIncidentRepository;
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
                new DecisionOutboxRecoveryStrategy(alertRepository),
                new FraudCaseUpdateRecoveryStrategy(mock(FraudCaseRepository.class)),
                new TrustIncidentRecoveryStrategy(mock(TrustIncidentRepository.class))
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
