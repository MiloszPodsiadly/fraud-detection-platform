package com.frauddetection.alert.security.redis;

import com.frauddetection.common.testsupport.container.RedisTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisAclIntegrationTest {

    private static final String FEATURE_KEY = "feature:acl-regression:feature-value";
    private static final String PROMOTION_KEY = "model-promotion:api-rate:v1:acl-regression";
    private static final String AUDIT_KEY = "audit:coverage:rate:acl-regression";
    private static final DefaultRedisScript<Long> SET_SCRIPT = new DefaultRedisScript<>(
            "return redis.call('SET', KEYS[1], ARGV[1]) and 1", Long.class);
    private static final DefaultRedisScript<Long> GLOBAL_DELETE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('FLUSHALL'); return 1", Long.class);

    @Container
    static final GenericContainer<?> REDIS = RedisTestSupport.newContainer();

    private static StringRedisTemplate feature;
    private static StringRedisTemplate alert;
    private static StringRedisTemplate admin;
    private static LettuceConnectionFactory featureConnections;
    private static LettuceConnectionFactory alertConnections;
    private static LettuceConnectionFactory adminConnections;

    @BeforeAll
    static void connectClients() {
        feature = RedisTestSupport.newFeatureTemplate(REDIS);
        alert = RedisTestSupport.newAlertTemplate(REDIS);
        admin = RedisTestSupport.newAdminTemplate(REDIS);
        featureConnections = (LettuceConnectionFactory) feature.getConnectionFactory();
        alertConnections = (LettuceConnectionFactory) alert.getConnectionFactory();
        adminConnections = (LettuceConnectionFactory) admin.getConnectionFactory();
    }

    @AfterAll
    static void closeClients() {
        featureConnections.destroy();
        alertConnections.destroy();
        adminConnections.destroy();
    }

    @BeforeEach
    void clearTestDatabase() {
        RedisTestSupport.flushDb(REDIS);
    }

    @Test
    void rejectsDisabledDefaultAndInvalidApplicationCredentials() {
        LettuceConnectionFactory anonymous = connectionFactory(null, null);
        LettuceConnectionFactory invalid = connectionFactory(
                RedisTestSupport.FEATURE_USERNAME, "invalid-password-that-is-not-configured");
        try {
            StringRedisTemplate anonymousClient = new StringRedisTemplate(anonymous);
            StringRedisTemplate invalidClient = new StringRedisTemplate(invalid);
            anonymousClient.afterPropertiesSet();
            invalidClient.afterPropertiesSet();
            assertThatThrownBy(() -> anonymousClient.opsForValue().get(FEATURE_KEY))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> invalidClient.opsForValue().get(FEATURE_KEY))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            anonymous.destroy();
            invalid.destroy();
        }
    }

    @Test
    void isolatesFeatureAlertAndAuditNamespacesInBothDirections() {
        admin.opsForValue().set(FEATURE_KEY, "feature-original");
        admin.opsForValue().set(PROMOTION_KEY, "promotion-original");
        admin.opsForValue().set(AUDIT_KEY, "audit-original");

        assertThat(feature.opsForValue().get(FEATURE_KEY)).isEqualTo("feature-original");
        feature.opsForValue().set("feature:acl-regression:feature-write", "feature-write-ok");
        assertThat(alert.opsForValue().get(PROMOTION_KEY)).isEqualTo("promotion-original");
        alert.opsForValue().set("model-promotion:api-rate:v1:alert-write", "alert-write-ok");
        alert.opsForValue().set("audit:coverage:rate:audit-write", "audit-write-ok");

        assertDenied(() -> feature.opsForValue().get(PROMOTION_KEY));
        assertDenied(() -> feature.opsForValue().set(PROMOTION_KEY, "tampered-by-feature"));
        assertDenied(() -> feature.opsForValue().get(AUDIT_KEY));
        assertDenied(() -> feature.opsForValue().set(AUDIT_KEY, "tampered-by-feature"));
        assertDenied(() -> alert.opsForValue().get(FEATURE_KEY));
        assertDenied(() -> alert.opsForValue().set(FEATURE_KEY, "tampered-by-alert"));

        assertThat(admin.opsForValue().get(FEATURE_KEY)).isEqualTo("feature-original");
        assertThat(admin.opsForValue().get(PROMOTION_KEY)).isEqualTo("promotion-original");
        assertThat(admin.opsForValue().get(AUDIT_KEY)).isEqualTo("audit-original");
    }

    @Test
    void restrictsGlobalAdminEnumerationAndDestructiveCommands() {
        for (StringRedisTemplate client : List.of(feature, alert)) {
            assertDenied(() -> execute(client, "FLUSHALL"));
            assertDenied(() -> execute(client, "FLUSHDB"));
            assertDenied(() -> execute(client, "CONFIG", "GET", "*"));
            assertDenied(() -> execute(client, "ACL", "LIST"));
            assertDenied(() -> execute(client, "INFO"));
            assertDenied(() -> execute(client, "KEYS", "*"));
            assertDenied(() -> execute(client, "SCAN", "0"));
        }

        admin.opsForValue().set(FEATURE_KEY, "survives-denied-commands");
        assertThat(admin.opsForValue().get(FEATURE_KEY)).isEqualTo("survives-denied-commands");
    }

    @Test
    void scriptsRunWithCallerPermissionsAndReconnectRetainsTheNarrowPolicy() {
        assertThat(feature.execute(SET_SCRIPT, List.of(FEATURE_KEY), "feature-script-value")).isEqualTo(1L);
        assertDenied(() -> feature.execute(SET_SCRIPT, List.of(PROMOTION_KEY), "cross-namespace-script"));
        assertDenied(() -> feature.execute(GLOBAL_DELETE_SCRIPT, List.of()));
        assertThat(admin.opsForValue().get(PROMOTION_KEY)).isNull();
        assertThat(admin.opsForValue().get(FEATURE_KEY)).isEqualTo("feature-script-value");

        featureConnections.destroy();
        featureConnections = RedisTestSupport.newConnectionFactory(REDIS,
                RedisTestSupport.featureUsername(), RedisTestSupport.featurePassword());
        feature = new StringRedisTemplate(featureConnections);
        feature.afterPropertiesSet();
        assertThat(feature.opsForValue().get(FEATURE_KEY)).isEqualTo("feature-script-value");
        assertThat(feature.execute(SET_SCRIPT, List.of("feature:acl-regression:after-reconnect"), "ok")).isEqualTo(1L);
    }

    private static LettuceConnectionFactory connectionFactory(String username, String password) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        if (username != null) {
            configuration.setUsername(username);
        }
        if (password != null) {
            configuration.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        return connectionFactory;
    }

    private static Object execute(StringRedisTemplate template, String command, String... arguments) {
        return template.execute((RedisCallback<Object>) connection -> connection.execute(command,
                java.util.Arrays.stream(arguments)
                        .map(argument -> argument.getBytes(StandardCharsets.UTF_8))
                        .toArray(byte[][]::new)));
    }

    private static void assertDenied(Runnable operation) {
        assertThatThrownBy(operation).isInstanceOf(RuntimeException.class);
    }
}
