package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ModelCardCurrentProperties.class)
class ModelCardProviderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "model-card.current", name = "enabled", havingValue = "true")
    ModelCardProvider artifactBackedModelCardProvider(
            ModelCardCurrentProperties properties,
            ObjectMapper objectMapper,
            ModelEvaluationGovernanceValidator validator
    ) {
        return new ArtifactBackedModelCardProvider(properties, objectMapper, validator);
    }

    @Bean
    @ConditionalOnMissingBean(ModelCardProvider.class)
    ModelCardProvider emptyModelCardProvider() {
        return new EmptyModelCardProvider();
    }
}
