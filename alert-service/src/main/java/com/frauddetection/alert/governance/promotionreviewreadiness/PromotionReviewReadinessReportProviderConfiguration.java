package com.frauddetection.alert.governance.promotionreviewreadiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PromotionReviewReadinessReportCurrentProperties.class)
class PromotionReviewReadinessReportProviderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "promotion-review-readiness.current", name = "enabled", havingValue = "true")
    PromotionReviewReadinessReportProvider artifactBackedPromotionReviewReadinessReportProvider(
            PromotionReviewReadinessReportCurrentProperties properties,
            ObjectMapper objectMapper,
            PromotionReviewReadinessReportValidator validator
    ) {
        return new ArtifactBackedPromotionReviewReadinessReportProvider(properties, objectMapper, validator);
    }

    @Bean
    @ConditionalOnMissingBean(PromotionReviewReadinessReportProvider.class)
    PromotionReviewReadinessReportProvider emptyPromotionReviewReadinessReportProvider() {
        return new EmptyPromotionReviewReadinessReportProvider();
    }
}
