package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class ModelPromotionApiConfiguration {

    @Bean
    ModelPromotionApiRateLimiter modelPromotionApiRateLimiter(
            @Value("${app.model-promotion.api.max-requests-per-minute:30}") int maxRequestsPerMinute,
            @Value("${app.model-promotion.api.max-tracked-actors:10000}") int maxTrackedActors,
            @Value("${app.model-promotion.api.rate-limiter.mode}") String mode,
            StringRedisTemplate redisTemplate,
            AlertServiceMetrics metrics
    ) {
        if ("redis".equalsIgnoreCase(mode)) {
            return new ModelPromotionApiRateLimiter(redisTemplate, Clock.systemUTC(), java.time.Duration.ofMinutes(1),
                    maxRequestsPerMinute, maxTrackedActors, metrics);
        }
        if ("in-memory".equalsIgnoreCase(mode)) {
            return new ModelPromotionApiRateLimiter(Clock.systemUTC(), maxRequestsPerMinute, maxTrackedActors, metrics);
        }
        throw new IllegalStateException("app.model-promotion.api.rate-limiter.mode must be redis or in-memory");
    }
}
