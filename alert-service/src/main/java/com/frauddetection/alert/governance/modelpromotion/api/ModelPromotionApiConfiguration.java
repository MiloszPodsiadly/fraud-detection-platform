package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
class ModelPromotionApiConfiguration {

    @Bean
    ModelPromotionRateLimitActorKeyResolver modelPromotionRateLimitActorKeyResolver(
            @Value("${app.security.demo-auth.enabled:false}") boolean demoAuthEnabled,
            @Value("${app.security.jwt.enabled:false}") boolean jwtAuthEnabled,
            Environment environment
    ) {
        return new ModelPromotionRateLimitActorKeyResolver(demoAuthEnabled, jwtAuthEnabled, environment);
    }

    @Bean
    ModelPromotionApiRateLimiter modelPromotionApiRateLimiter(
            @Value("${app.model-promotion.api.max-requests-per-minute:30}") int maxRequestsPerMinute,
            @Value("${app.model-promotion.api.max-tracked-actors:10000}") int maxTrackedActors,
            @Value("${app.model-promotion.api.rate-limiter.mode}") String mode,
            StringRedisTemplate redisTemplate,
            AlertServiceMetrics metrics,
            Environment environment
    ) {
        if ("redis".equalsIgnoreCase(mode)) {
            return new ModelPromotionApiRateLimiter(redisTemplate, Clock.systemUTC(), java.time.Duration.ofMinutes(1),
                    maxRequestsPerMinute, maxTrackedActors, metrics);
        }
        if ("in-memory".equalsIgnoreCase(mode)) {
            boolean productionLike = Arrays.stream(environment.getActiveProfiles())
                    .map(profile -> profile.toLowerCase(Locale.ROOT))
                    .anyMatch(profile -> profile.equals("prod") || profile.equals("production")
                            || profile.equals("staging") || profile.equals("bank"));
            if (productionLike) {
                throw new IllegalStateException("In-memory model-promotion rate limiting is forbidden in "
                        + "production-like profiles; configure standalone Redis.");
            }
            return new ModelPromotionApiRateLimiter(Clock.systemUTC(), maxRequestsPerMinute, maxTrackedActors, metrics);
        }
        throw new IllegalStateException("app.model-promotion.api.rate-limiter.mode must be redis or in-memory");
    }
}
