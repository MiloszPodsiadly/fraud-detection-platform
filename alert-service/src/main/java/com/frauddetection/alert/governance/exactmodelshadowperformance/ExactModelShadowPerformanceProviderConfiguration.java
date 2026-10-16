package com.frauddetection.alert.governance.exactmodelshadowperformance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(ExactModelShadowPerformanceProperties.class)
class ExactModelShadowPerformanceProviderConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "model-shadow-performance.artifacts",
            name = "enabled",
            havingValue = "true"
    )
    ExactModelShadowPerformanceProvider artifactBackedExactModelShadowPerformanceProvider(
            ExactModelShadowPerformanceProperties properties,
            ObjectMapper objectMapper,
            ExactModelShadowPerformanceValidator validator
    ) {
        return new ArtifactBackedExactModelShadowPerformanceProvider(properties, objectMapper, validator);
    }

    @Bean
    @ConditionalOnMissingBean(ExactModelShadowPerformanceProvider.class)
    ExactModelShadowPerformanceProvider emptyExactModelShadowPerformanceProvider() {
        return new EmptyExactModelShadowPerformanceProvider();
    }
}
