package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelPromotionApiConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelPromotionApiConfiguration.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(AlertServiceMetrics.class, () -> mock(AlertServiceMetrics.class));

    @Test
    void inMemoryLimiterIsAllowedForLocalAndTestProfiles() {
        for (String[] profiles : new String[][] { {}, {"test"}, {"bank-local"} }) {
            contextRunner.withPropertyValues("app.model-promotion.api.rate-limiter.mode=in-memory")
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context).hasNotFailed()
                            .hasSingleBean(ModelPromotionApiRateLimiter.class));
        }
    }

    @Test
    void inMemoryLimiterIsRejectedForProductionLikeAndMixedProfiles() {
        for (String[] profiles : new String[][] {
                {"prod"}, {"production"}, {"staging"}, {"bank"}, {"test", "prod"}
        }) {
            contextRunner.withPropertyValues("app.model-promotion.api.rate-limiter.mode=in-memory")
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "In-memory model-promotion rate limiting is forbidden in production-like profiles; configure standalone Redis."));
        }
    }

    @Test
    void blankAndUnknownLimiterModesFailAtStartup() {
        for (String mode : new String[] {"", "local", "cluster"}) {
            contextRunner.withPropertyValues("app.model-promotion.api.rate-limiter.mode=" + mode)
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "app.model-promotion.api.rate-limiter.mode must be redis or in-memory"));
        }
    }

    @Test
    void productionLikeProfileCanUseConfiguredSharedRedisLimiter() {
        contextRunner.withPropertyValues("app.model-promotion.api.rate-limiter.mode=redis")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ModelPromotionApiRateLimiter.class));
    }
}
