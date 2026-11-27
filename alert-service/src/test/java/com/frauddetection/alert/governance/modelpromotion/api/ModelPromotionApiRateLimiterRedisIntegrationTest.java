package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.common.testsupport.container.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ModelPromotionApiRateLimiterRedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = RedisTestSupport.newContainer();

    private LettuceConnectionFactory connections;
    private StringRedisTemplate template;
    private LettuceConnectionFactory adminConnections;
    private StringRedisTemplate adminTemplate;
    private ModelPromotionApiRateLimiter first;
    private ModelPromotionApiRateLimiter second;

    @BeforeEach
    void setUp() {
        connections = RedisTestSupport.newConnectionFactory(REDIS,
                RedisTestSupport.alertUsername(), RedisTestSupport.alertPassword());
        template = new StringRedisTemplate(connections);
        template.afterPropertiesSet();
        adminTemplate = RedisTestSupport.newAdminTemplate(REDIS);
        adminConnections = (LettuceConnectionFactory) adminTemplate.getConnectionFactory();
        first = limiter(30, 100, Duration.ofSeconds(30));
        second = limiter(30, 100, Duration.ofSeconds(30));
        adminTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        if (connections != null) connections.destroy();
        if (adminConnections != null) adminConnections.destroy();
    }

    @Test
    void independentLimiterInstancesShareOneActorAndTenantScopedQuota() {
        first = limiter(1, 100, Duration.ofSeconds(30));
        second = limiter(1, 100, Duration.ofSeconds(30));

        assertThat(first.check("issuer-a#actor-1")).isEqualTo(ModelPromotionApiRateLimiter.Decision.ALLOWED);
        assertThat(second.check("issuer-a#actor-1")).isEqualTo(ModelPromotionApiRateLimiter.Decision.RATE_LIMITED);
        assertThat(second.check("issuer-b#actor-1")).isEqualTo(ModelPromotionApiRateLimiter.Decision.ALLOWED);
    }

    @Test
    void concurrentRequestsAcrossInstancesCannotExceedSharedQuota() throws Exception {
        int limit = 8;
        first = limiter(limit, 100, Duration.ofSeconds(30));
        second = limiter(limit, 100, Duration.ofSeconds(30));
        List<java.util.concurrent.Future<ModelPromotionApiRateLimiter.Decision>> requests = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 64; i++) {
                ModelPromotionApiRateLimiter instance = i % 2 == 0 ? first : second;
                requests.add(executor.submit(() -> instance.check("issuer-a#concurrent-actor")));
            }
            long allowed = 0;
            for (var request : requests) {
                if (request.get(10, TimeUnit.SECONDS) == ModelPromotionApiRateLimiter.Decision.ALLOWED) allowed++;
            }
            assertThat(allowed).isEqualTo(limit);
        }
    }

    @Test
    void quotaExpiresAndActiveActorIndexRemainsBounded() throws Exception {
        first = limiter(1, 1, Duration.ofMillis(120));
        second = limiter(1, 1, Duration.ofMillis(120));

        assertThat(first.check("issuer-a#actor-1")).isEqualTo(ModelPromotionApiRateLimiter.Decision.ALLOWED);
        assertThat(second.check("issuer-a#actor-2")).isEqualTo(ModelPromotionApiRateLimiter.Decision.UNAVAILABLE);
        Thread.sleep(180);
        assertThat(second.check("issuer-a#actor-2")).isEqualTo(ModelPromotionApiRateLimiter.Decision.ALLOWED);
    }

    private ModelPromotionApiRateLimiter limiter(int requestLimit, int actorLimit, Duration window) {
        return new ModelPromotionApiRateLimiter(template, Clock.systemUTC(), window, requestLimit, actorLimit, null);
    }
}
