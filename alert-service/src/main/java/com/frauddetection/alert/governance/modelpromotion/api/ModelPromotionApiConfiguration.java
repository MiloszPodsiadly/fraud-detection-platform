package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.runtime.InstitutionalModePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
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
            Environment environment,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        if ("redis".equalsIgnoreCase(mode)) {
            return new ModelPromotionApiRateLimiter(redisTemplate, Clock.systemUTC(), java.time.Duration.ofMinutes(1),
                    maxRequestsPerMinute, maxTrackedActors, metrics);
        }
        if ("in-memory".equalsIgnoreCase(mode)) {
            if (InstitutionalModePolicy.isInstitutionalMode(environment, bankModeFailClosed)) {
                throw new IllegalStateException("In-memory model-promotion rate limiting is forbidden in "
                        + "institutional mode; configure standalone Redis.");
            }
            if (!InstitutionalModePolicy.isExplicitLocalSingleNodeMode(environment)) {
                throw new IllegalStateException("In-memory model-promotion rate limiting requires an explicit "
                        + "local/dev/test/docker-local/bank-local profile; configure standalone Redis otherwise.");
            }
            return new ModelPromotionApiRateLimiter(Clock.systemUTC(), maxRequestsPerMinute, maxTrackedActors, metrics);
        }
        throw new IllegalStateException("app.model-promotion.api.rate-limiter.mode must be redis or in-memory");
    }
}
