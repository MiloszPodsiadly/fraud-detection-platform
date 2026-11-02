package com.frauddetection.scoring.merchant;

import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.config.EngineIntelligenceRuntimeConfig;
import com.frauddetection.scoring.config.MerchantRiskEngineConfigurationValidator;
import com.frauddetection.scoring.config.MerchantRiskEngineProperties;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantRiskEngineIsolationTest {
    private static final String FAILURE = MerchantRiskEngineProperties.PROPERTY_NAME
            + " cannot be enabled: independent merchant occurrence admission and downstream contract approval are unavailable";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "demo-auth", "test", "prod"})
    void defaultAndExplicitlyDisabledProfilesContainNoMerchantEngineAccessOrExecutor(String profile) {
        runner().withInitializer(context -> context.getEnvironment().setActiveProfiles(profile)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MerchantRiskEngineProperties.class).enabled()).isFalse();
            assertThat(context).hasSingleBean(MerchantRiskEngineConfigurationValidator.class);
            assertThat(context).doesNotHaveBean(MerchantRiskSignalEngine.class)
                    .doesNotHaveBean(MerchantProfileAccess.class).doesNotHaveBean(MerchantRiskSignalPolicy.class)
                    .doesNotHaveBean(BoundedFraudEngineExecutor.class);
        });
        runner().withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues(MerchantRiskEngineProperties.PROPERTY_NAME + "=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MerchantRiskSignalEngine.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "demo-auth", "test", "prod"})
    void everyProfileRejectsLiveEnablementRegardlessOfDiagnosticEmissionMode(String profile) {
        for (boolean emission : new boolean[] {false, true}) {
            (emission ? enabledRunner() : runner())
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                    .withPropertyValues(MerchantRiskEngineProperties.PROPERTY_NAME + "=true")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseMessage(FAILURE);
                    });
        }
    }

    @Test
    void canonicalEnvironmentEnablementAlsoFailsStartup() {
        runner().withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("merchant-test-only-env", Map.of("FRAUD_SCORING_ENGINES_MERCHANT_ENABLED", "true"))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(FAILURE);
                });
    }

    @Test
    void applicationYamlKeepsTheDefaultFalseAndItsEnvironmentAliasCannotBypassTheGuard() {
        yamlRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MerchantRiskEngineProperties.class).enabled()).isFalse();
        });
        yamlRunner().withPropertyValues("FRAUD_SCORING_ENGINE_MERCHANT_ENABLED=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(FAILURE);
        });
    }

    @Test
    void lazyApplicationSettingsCannotDeferTheActivationGuard() {
        runner().withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withPropertyValues("spring.main.lazy-initialization=true", MerchantRiskEngineProperties.PROPERTY_NAME + "=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(FAILURE);
                });
    }

    @Test
    void componentScanningDiscoversNoMerchantCandidateOrAccessProvider() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        assertThat(scanner.findCandidateComponents("com.frauddetection.scoring.merchant")).isEmpty();
    }

    @Test
    void enablingExistingDiagnosticsAndVelocityCannotIntroduceMerchantIntoTheRuntimeGraph() {
        var rules = mock(RuleBasedFraudScoringEngine.class);
        var ml = mock(MlFraudScoringEngine.class);
        runner().withPropertyValues(EngineIntelligenceEmissionProperties.PROPERTY_NAME + "=true")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(RuleBasedFraudScoringEngine.class, () -> rules)
                .withBean(MlFraudScoringEngine.class, () -> ml)
                .withPropertyValues("fraud.scoring.engines.velocity.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(MerchantRiskSignalEngine.class)
                            .doesNotHaveBean(MerchantProfileAccess.class).doesNotHaveBean(MerchantRiskSignalPolicy.class);
                    assertThat(context.getBean(FraudSignalEngineRegistry.class).orderedEngines())
                            .extracting(engine -> engine.descriptor().engineId()).containsExactly("rules.primary", "ml.python.primary", "velocity.primary");
                    verifyNoInteractions(rules, ml);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(EngineIntelligenceRuntimeConfig.class)
                .withBean(ScoringProperties.class, () -> new ScoringProperties(0.75, 0.90, ScoringMode.RULE_BASED));
    }
    private static ApplicationContextRunner enabledRunner() {
        return runner().withPropertyValues(EngineIntelligenceEmissionProperties.PROPERTY_NAME + "=true")
                .withBean(RuleBasedFraudScoringEngine.class, () -> mock(RuleBasedFraudScoringEngine.class))
                .withBean(MlFraudScoringEngine.class, () -> mock(MlFraudScoringEngine.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }
    private static ApplicationContextRunner yamlRunner() {
        return runner().withInitializer(context -> {
            try {
                var yaml = new YamlPropertySourceLoader().load("merchant-scoring-test-yaml", new ClassPathResource("application.yml"));
                yaml.forEach(propertySource -> context.getEnvironment().getPropertySources().addLast(propertySource));
            } catch (IOException exception) { throw new IllegalStateException(exception); }
        });
    }
}
