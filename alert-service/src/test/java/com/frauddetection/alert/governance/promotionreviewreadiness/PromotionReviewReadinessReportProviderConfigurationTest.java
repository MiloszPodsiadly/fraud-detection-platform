package com.frauddetection.alert.governance.promotionreviewreadiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionReviewReadinessReportProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PromotionReviewReadinessReportProviderConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PromotionReviewReadinessReportValidator.class, PromotionReviewReadinessReportValidator::new);

    @Test
    void defaultConfigurationUsesEmptyProviderOnly() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PromotionReviewReadinessReportProvider.class);
            assertThat(context.getBean(PromotionReviewReadinessReportProvider.class))
                    .isInstanceOf(EmptyPromotionReviewReadinessReportProvider.class);
        });
    }

    @Test
    void artifactProviderRequiresExplicitEnablement() {
        contextRunner
                .withPropertyValues(
                        "promotion-review-readiness.current.enabled=true",
                        "promotion-review-readiness.current.base-dir=/tmp",
                        "promotion-review-readiness.current.path=/tmp/current-report.json"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PromotionReviewReadinessReportProvider.class);
                    assertThat(context.getBean(PromotionReviewReadinessReportProvider.class))
                            .isInstanceOf(ArtifactBackedPromotionReviewReadinessReportProvider.class);
                });
    }

    @Test
    void sourcePathMustBeExplicitEvenWhenPropertiesBind() {
        contextRunner
                .withPropertyValues("promotion-review-readiness.current.enabled=true")
                .run(context -> assertThat(context.getBean(PromotionReviewReadinessReportProvider.class).currentReport()).isEmpty());
    }
}
