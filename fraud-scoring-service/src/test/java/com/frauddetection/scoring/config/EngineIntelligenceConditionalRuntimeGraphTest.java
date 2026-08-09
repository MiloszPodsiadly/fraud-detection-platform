package com.frauddetection.scoring.config;

import com.frauddetection.scoring.engine.ml.PythonMlSignalEngine;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalEngine;
import com.frauddetection.scoring.engine.velocity.VelocitySignalEngine;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceDiagnosticEnrichmentPipeline;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionMetrics;
import com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionService;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.NoOpEngineIntelligenceEmissionMetrics;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Optional;

import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.contextRunner;
import static com.frauddetection.scoring.config.EngineIntelligenceSpringContextTestSupport.enabledContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EngineIntelligenceConditionalRuntimeGraphTest {

    @Test
    void defaultDisabledContextHasLightBoundaryOnly() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EngineIntelligenceEmissionService.class);
            assertThat(context).hasSingleBean(EngineIntelligenceRuntimeConfigurationValidator.class);
            assertThat(context).hasSingleBean(EngineIntelligenceEmissionMetrics.class);
            assertThat(context).hasSingleBean(NoOpEngineIntelligenceEmissionMetrics.class);
            assertThat(context).doesNotHaveBean(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
            assertThat(context).doesNotHaveBean(FraudScoringOrchestrator.class);
            assertThat(context).doesNotHaveBean(FraudSignalEngineRegistry.class);
            assertThat(context).doesNotHaveBean(RuleBasedSignalEngine.class);
            assertThat(context).doesNotHaveBean(PythonMlSignalEngine.class);
            assertThat(context).doesNotHaveBean(BoundedFraudEngineExecutor.class);
            assertThat(context).doesNotHaveBean(FraudEngineAggregationService.class);
            assertThat(context).doesNotHaveBean(PublicEngineIntelligenceMapper.class);
        });
    }

    @Test
    void enabledContextCreatesDiagnosticRuntime() {
        enabledContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
            assertThat(context).hasSingleBean(FraudScoringOrchestrator.class);
            assertThat(context).hasSingleBean(FraudSignalEngineRegistry.class);
            assertThat(context).hasSingleBean(RuleBasedSignalEngine.class);
            assertThat(context).hasSingleBean(PythonMlSignalEngine.class);
            assertThat(context).doesNotHaveBean(VelocitySignalEngine.class);
            assertThat(context).hasSingleBean(BoundedFraudEngineExecutor.class);
            assertThat(context).hasSingleBean(FraudEngineAggregationService.class);
            assertThat(context).hasSingleBean(PublicEngineIntelligenceMapper.class);
        });
    }

    @Test
    void disabledEmissionDoesNotResolveObjectProvider() {
        ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> provider = provider();
        var service = service(false, provider);

        assertThat(service.emitIfEnabled(request())).isEmpty();
        verifyNoInteractions(provider);
    }

    @Test
    void enabledEmissionResolvesObjectProviderOnce() {
        EngineIntelligenceDiagnosticEnrichmentPipeline pipeline =
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
        ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> provider = provider();
        when(provider.getIfAvailable()).thenReturn(pipeline);
        when(pipeline.enrich(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        var service = service(true, provider);

        assertThat(service.emitIfEnabled(request())).isEmpty();
        verify(provider).getIfAvailable();
    }

    @Test
    void disabledContextDoesNotRequireMlFraudScoringEngine() {
        contextRunner().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void applicationYmlExposesVelocityBeanPropertyPath() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        var properties = yaml.getObject();

        assertThat(properties).containsKey("fraud.scoring.engines.velocity.enabled");
        assertThat(properties).doesNotContainKey("fraud.engines.velocity.enabled");
    }

    @Test
    void enabledVelocityContextCreatesSingleOptionalVelocityEngineInRegistryOrder() {
        enabledContextRunner()
                .withPropertyValues("fraud.scoring.engines.velocity.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(VelocitySignalEngine.class);
                    assertThat(context.getBean(VelocitySignalEngine.class).descriptor().required()).isFalse();
                    assertThat(context.getBean(FraudSignalEngineRegistry.class).orderedEngines())
                            .extracting(engine -> engine.descriptor().engineId())
                            .containsExactly("rules.primary", "ml.python.primary", "velocity.primary");
                });
    }

    @Test
    void disabledVelocityContextKeepsTwoEngineRegistryOrder() {
        enabledContextRunner()
                .withPropertyValues("fraud.scoring.engines.velocity.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(VelocitySignalEngine.class);
                    assertThat(context.getBean(FraudSignalEngineRegistry.class).orderedEngines())
                            .extracting(engine -> engine.descriptor().engineId())
                            .containsExactly("rules.primary", "ml.python.primary");
                });
    }

    @Test
    void absentVelocityPropertyKeepsVelocityBeanAbsent() {
        enabledContextRunner().run(context -> assertThat(context).doesNotHaveBean(VelocitySignalEngine.class));
    }

    @Test
    void contradictoryDisabledEmissionAndEnabledVelocityFailsFast() {
        contextRunner()
                .withPropertyValues(
                        "fraud.scoring.events.engine-intelligence.emit-enabled=false",
                        "fraud.scoring.engines.velocity.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "fraud.scoring.events.engine-intelligence.emit-enabled must be true when "
                                            + "fraud.scoring.engines.velocity.enabled is true"
                            );
                });
    }

    @Test
    void emissionAndVelocityFlagMatrixIsExplicit() {
        contextRunner()
                .withPropertyValues(
                        "fraud.scoring.events.engine-intelligence.emit-enabled=false",
                        "fraud.scoring.engines.velocity.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FraudSignalEngineRegistry.class);
                    assertThat(context).doesNotHaveBean(VelocitySignalEngine.class);
                });
        enabledContextRunner()
                .withPropertyValues("fraud.scoring.engines.velocity.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FraudSignalEngineRegistry.class);
                    assertThat(context).doesNotHaveBean(VelocitySignalEngine.class);
                });
        enabledContextRunner()
                .withPropertyValues("fraud.scoring.engines.velocity.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FraudSignalEngineRegistry.class);
                    assertThat(context).hasSingleBean(VelocitySignalEngine.class);
                });
    }

    private EngineIntelligenceEmissionService service(
            boolean enabled,
            ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> provider
    ) {
        return new EngineIntelligenceEmissionService(
                new EngineIntelligenceEmissionProperties(enabled),
                provider,
                new NoOpEngineIntelligenceEmissionMetrics()
        );
    }

    private FraudScoringRequest request() {
        return FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> provider() {
        return mock(ObjectProvider.class);
    }
}
