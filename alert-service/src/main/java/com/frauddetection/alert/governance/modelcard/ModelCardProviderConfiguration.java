package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ModelCardCurrentProperties.class, ModelCardArtifactProperties.class})
class ModelCardProviderConfiguration {

    @Bean
    @ConditionalOnExpression("${model-card.current.enabled:false} || ${model-card.artifacts.enabled:false}")
    ModelCardProvider artifactBackedModelCardProvider(
            ModelCardCurrentProperties currentProperties,
            ModelCardArtifactProperties artifactProperties,
            ObjectMapper objectMapper,
            ModelCardValidator validator
    ) {
        return new ArtifactBackedModelCardProvider(currentProperties, artifactProperties, objectMapper, validator);
    }

    @Bean
    @ConditionalOnMissingBean(ModelCardProvider.class)
    ModelCardProvider emptyModelCardProvider() {
        return new EmptyModelCardProvider();
    }
}
