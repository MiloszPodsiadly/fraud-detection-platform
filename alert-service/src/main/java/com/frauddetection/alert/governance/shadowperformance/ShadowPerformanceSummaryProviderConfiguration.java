package com.frauddetection.alert.governance.shadowperformance;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ShadowPerformanceSummaryCurrentProperties.class)
class ShadowPerformanceSummaryProviderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shadow-performance.summary.current", name = "enabled", havingValue = "true")
    ShadowPerformanceSummaryProvider artifactBackedShadowPerformanceSummaryProvider(
            ShadowPerformanceSummaryCurrentProperties properties,
            ObjectMapper objectMapper,
            ShadowPerformanceSummaryValidator validator
    ) {
        return new ArtifactBackedShadowPerformanceSummaryProvider(properties, objectMapper, validator);
    }

    @Bean
    @ConditionalOnMissingBean(ShadowPerformanceSummaryProvider.class)
    ShadowPerformanceSummaryProvider emptyShadowPerformanceSummaryProvider() {
        return new EmptyShadowPerformanceSummaryProvider();
    }
}
