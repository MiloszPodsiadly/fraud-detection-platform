package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCardProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelCardProviderConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ModelEvaluationGovernanceValidator.class, ModelEvaluationGovernanceValidator::new)
            .withBean(ModelCardValidator.class,
                    () -> new ModelCardValidator(new ModelEvaluationGovernanceValidator()));

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
    void enabledProviderRejectsMissingOrBlankCurrentPath() {
        assertStartupFailure(
                new String[]{"model-card.current.enabled=true"},
                "model-card.current.path must be configured when enabled");
        assertStartupFailure(
                new String[]{"model-card.current.enabled=true", "model-card.current.path="},
                "model-card.current.path must be configured when enabled");
        assertStartupFailure(
                new String[]{"model-card.current.enabled=true", "model-card.current.path=   "},
                "model-card.current.path must be configured when enabled");
    }

    @Test
    void enabledProviderRejectsInvalidCardSizeLimits() {
        assertStartupFailure(properties("model-card.current.max-card-size-bytes=0"),
                "model-card.current.max-card-size-bytes must be between 1 and 524288 when enabled");
        assertStartupFailure(properties("model-card.current.max-card-size-bytes=-1"),
                "model-card.current.max-card-size-bytes must be between 1 and 524288 when enabled");
        assertStartupFailure(properties("model-card.current.max-card-size-bytes=524289"),
                "model-card.current.max-card-size-bytes must be between 1 and 524288 when enabled");
    }

    @Test
    void enabledProviderRejectsInvalidMarkdownSizeLimits() {
        assertStartupFailure(properties("model-card.current.max-markdown-size-bytes=0"),
                "model-card.current.max-markdown-size-bytes must be between 1 and 262144 when enabled");
        assertStartupFailure(properties("model-card.current.max-markdown-size-bytes=-1"),
                "model-card.current.max-markdown-size-bytes must be between 1 and 262144 when enabled");
        assertStartupFailure(properties("model-card.current.max-markdown-size-bytes=262145"),
                "model-card.current.max-markdown-size-bytes must be between 1 and 262144 when enabled");
    }

    @Test
    void enabledProviderAcceptsOmittedCustomAndMaximumSizeLimits() {
        assertLimits(properties(), 524_288L, 262_144L);
        assertLimits(properties(
                "model-card.current.max-card-size-bytes=1024",
                "model-card.current.max-markdown-size-bytes=512"), 1_024L, 512L);
        assertLimits(properties(
                "model-card.current.max-card-size-bytes=524288",
                "model-card.current.max-markdown-size-bytes=262144"), 524_288L, 262_144L);
    }

    private String[] properties(String... additional) {
        String[] properties = new String[additional.length + 2];
        properties[0] = "model-card.current.enabled=true";
        properties[1] = "model-card.current.path=/tmp/ml_model_card.json";
        System.arraycopy(additional, 0, properties, 2, additional.length);
        return properties;
    }

    private void assertStartupFailure(String[] properties, String message) {
        contextRunner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(message);
        });
    }

    private void assertLimits(String[] properties, long cardLimit, long markdownLimit) {
        contextRunner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasNotFailed();
            ModelCardCurrentProperties bound = context.getBean(ModelCardCurrentProperties.class);
            assertThat(bound.maxCardSizeBytes()).isEqualTo(cardLimit);
            assertThat(bound.maxMarkdownSizeBytes()).isEqualTo(markdownLimit);
        });
    }
}
