package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelPromotionApiConfigurationTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelPromotionApiConfiguration.class)
            .withBean(StringRedisTemplate.class, () -> redisTemplate)
            .withBean(AlertServiceMetrics.class, () -> mock(AlertServiceMetrics.class));

    @Test
    void inMemoryLimiterIsAllowedOnlyForExplicitSingleNodeProfiles() {
        for (String[] profiles : new String[][] {
                {"local"}, {"dev"}, {"test"}, {"docker-local"}, {"bank-local"}
        }) {
            contextRunner.withPropertyValues("app.model-promotion.api.rate-limiter.mode=in-memory")
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context).hasNotFailed()
                            .hasSingleBean(ModelPromotionApiRateLimiter.class));
        }
    }

    @Test
    void inMemoryLimiterIsRejectedWithoutAnExplicitLocalProfile() {
        for (String[] profiles : new String[][] { {}, {"default"}, {"docker"}, {"unexpected"} }) {
            contextRunner.withPropertyValues("app.model-promotion.api.rate-limiter.mode=in-memory")
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("In-memory model-promotion rate limiting requires an explicit "
                                    + "local/dev/test/docker-local/bank-local profile; configure standalone Redis otherwise."));
        }
    }

    @Test
    void inMemoryLimiterIsRejectedForInstitutionalAndMixedProfiles() {
        for (String[] profiles : new String[][] {
                {"prod"}, {"production"}, {"staging"}, {"bank"}, {"test", "prod"}
        }) {
            contextRunner.withPropertyValues("app.model-promotion.api.rate-limiter.mode=in-memory")
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("In-memory model-promotion rate limiting is forbidden in "
                                    + "institutional mode; configure standalone Redis."));
        }
    }

    @Test
    void bankFailClosedFlagRejectsInMemoryLimiterRegardlessOfProfileName() {
        for (String[] profiles : new String[][] { {}, {"default"}, {"bank-local"}, {"unexpected"} }) {
            contextRunner.withPropertyValues(
                            "app.model-promotion.api.rate-limiter.mode=in-memory",
                            "app.audit.bank-mode.fail-closed=true")
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("In-memory model-promotion rate limiting is forbidden in "
                                    + "institutional mode; configure standalone Redis."));
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
    void productionLikeProfileUsesTheConfiguredSharedRedisLimiterWithoutMemoryFallback() {
        contextRunner.withPropertyValues("app.model-promotion.api.rate-limiter.mode=redis")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ModelPromotionApiRateLimiter.class);
                    ModelPromotionApiRateLimiter limiter = context.getBean(ModelPromotionApiRateLimiter.class);
                    assertThat(limiter.check("actor-1")).isEqualTo(ModelPromotionApiRateLimiter.Decision.UNAVAILABLE);
                    verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
                });
    }
}
