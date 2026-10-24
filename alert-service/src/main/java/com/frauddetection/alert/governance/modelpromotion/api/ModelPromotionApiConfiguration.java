package com.frauddetection.alert.governance.modelpromotion.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class ModelPromotionApiConfiguration {

    @Bean
    ModelPromotionApiRateLimiter modelPromotionApiRateLimiter(
            @Value("${app.model-promotion.api.max-requests-per-minute:30}") int maxRequestsPerMinute,
            @Value("${app.model-promotion.api.max-tracked-actors:10000}") int maxTrackedActors
    ) {
        return new ModelPromotionApiRateLimiter(Clock.systemUTC(), maxRequestsPerMinute, maxTrackedActors);
    }
}
