package com.frauddetection.alert.governance.shadowperformance;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowPerformanceSummaryProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ShadowPerformanceSummaryProviderConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ShadowPerformanceSummaryValidator.class, ShadowPerformanceSummaryValidator::new);

    @Test
    void defaultConfigurationUsesEmptyProviderOnly() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ShadowPerformanceSummaryProvider.class);
            assertThat(context.getBean(ShadowPerformanceSummaryProvider.class))
                    .isInstanceOf(EmptyShadowPerformanceSummaryProvider.class);
        });
    }

    @Test
    void artifactProviderRequiresExplicitEnablement() {
        contextRunner
                .withPropertyValues(
                        "shadow-performance.summary.current.enabled=true",
                        "shadow-performance.summary.current.base-dir=/tmp",
                        "shadow-performance.summary.current.path=/tmp/current-summary.json"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ShadowPerformanceSummaryProvider.class);
                    assertThat(context.getBean(ShadowPerformanceSummaryProvider.class))
                            .isInstanceOf(ArtifactBackedShadowPerformanceSummaryProvider.class);
                });
    }

    @Test
    void applicationContextDoesNotLoadStaticFixtureProvider() {
        contextRunner.run(context -> assertThat(context.getBeanNamesForType(ShadowPerformanceSummaryProvider.class))
                .doesNotContain("staticShadowPerformanceSummaryProvider"));
    }

    @Test
    void sourcePathMustBeExplicitEvenWhenPropertiesBind() {
        contextRunner
                .withPropertyValues("shadow-performance.summary.current.enabled=true")
                .run(context -> assertThat(context.getBean(ShadowPerformanceSummaryProvider.class).currentSummary()).isEmpty());
    }
}
