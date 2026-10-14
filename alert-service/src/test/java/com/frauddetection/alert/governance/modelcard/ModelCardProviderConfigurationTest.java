package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCardProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelCardProviderConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ModelCardValidator.class, ModelCardValidator::new);

    @Test
    void defaultConfigurationUsesEmptyProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ModelCardProvider.class);
            assertThat(context.getBean(ModelCardProvider.class)).isInstanceOf(EmptyModelCardProvider.class);
        });
    }

    @Test
    void artifactProviderRequiresExplicitEnablement() {
        contextRunner.withPropertyValues(
                "model-card.current.enabled=true",
                "model-card.current.base-dir=/tmp",
                "model-card.current.path=/tmp/ml_model_card.json"
        ).run(context -> {
            assertThat(context).hasSingleBean(ModelCardProvider.class);
            assertThat(context.getBean(ModelCardProvider.class)).isInstanceOf(ArtifactBackedModelCardProvider.class);
        });
    }

    @Test
    void enabledProviderStillRequiresExplicitCurrentPath() {
        contextRunner.withPropertyValues("model-card.current.enabled=true")
                .run(context -> assertThat(context.getBean(ModelCardProvider.class).currentCard()).isEmpty());
    }
}
