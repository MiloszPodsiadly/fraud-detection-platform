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
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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

    @org.testcontainers.junit.jupiter.Container
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

    @Test
    void productionEntrypointIgnoresTestAdminEnvironmentAndRunsRedisAsItsImageUser() throws Exception {
        GenericContainer<?> runtime = RedisTestSupport.newRuntimeContainerWithTestAdminEnvironment();
        runtime.start();
        try {
            Container.ExecResult identity = runtime.execInContainer("sh", "-c",
                    "redis_uid=$(id -u redis); redis_gid=$(id -g redis); " +
                            "set -- $(awk '/^Uid:/ {uid=$2} /^Gid:/ {gid=$2} END {print uid, gid}' /proc/1/status); " +
                            "test \"$1\" = \"$redis_uid\" && test \"$2\" = \"$redis_gid\" && test \"$1\" -ne 0");
            assertThat(identity.getExitCode()).as(identity.getStderr()).isZero();

            Container.ExecResult aclPermissions = runtime.execInContainer("sh", "-c",
                    "acl_file=$(awk '$0 == \"--aclfile\" {getline; print; exit}' /proc/1/cmdline); " +
                            "test -n \"$acl_file\" && " +
                            "test \"$(stat -c '%U:%G:%a' \"$acl_file\")\" = redis:redis:400 && " +
                            "test \"$(stat -c '%U:%G:%a' \"$(dirname \"$acl_file\")\")\" = redis:redis:700");
            assertThat(aclPermissions.getExitCode()).as(aclPermissions.getStderr()).isZero();

            LettuceConnectionFactory testAdminConnection = RedisTestSupport.newConnectionFactory(
                    runtime, RedisTestSupport.testAdminUsername(), RedisTestSupport.testAdminPassword());
            try {
                StringRedisTemplate testAdminClient = new StringRedisTemplate(testAdminConnection);
                testAdminClient.afterPropertiesSet();
                assertThatThrownBy(() -> testAdminClient.opsForValue().get("feature:runtime-admin-check"))
                        .isInstanceOf(RuntimeException.class);
            } finally {
                testAdminConnection.destroy();
            }
        } finally {
            runtime.stop();
        }
    }

    @Test
    void applicationDataAndTtlSurviveRecreationWithAclOnTheSameSyntheticDataDirectory() throws Exception {
        Path dataDirectory = Files.createTempDirectory("fdp157-redis-upgrade-");
        GenericContainer<?> legacy = new GenericContainer<>(DockerImageName.parse(RedisTestSupport.IMAGE))
                .withExposedPorts(6379)
                .withFileSystemBind(dataDirectory.toString(), "/data", BindMode.READ_WRITE)
                .withCreateContainerCmdModifier(command -> {
                    command.withUser("0");
                    command.withEntrypoint("/bin/sh");
                })
                .withCommand("-c", "chown redis:redis /data && exec /usr/local/bin/docker-entrypoint.sh redis-server --dir /data --dbfilename dump.rdb");
        GenericContainer<?> runtime = null;
        LettuceConnectionFactory runtimeConnections = null;
        try {
            legacy.start();
            Container.ExecResult write = legacy.execInContainer("redis-cli", "SET", "feature:persisted:upgrade", "retained-value", "EX", "3600");
            assertThat(write.getExitCode()).as(write.getStderr()).isZero();
            Container.ExecResult save = legacy.execInContainer("redis-cli", "SAVE");
            assertThat(save.getExitCode()).as(save.getStderr()).isZero();
            legacy.stop();

            runtime = RedisTestSupport.newRuntimeContainerWithTestAdminEnvironment()
                    .withFileSystemBind(dataDirectory.toString(), "/data", BindMode.READ_WRITE);
            runtime.start();
            Container.ExecResult dataPermissions = runtime.execInContainer("sh", "-c",
                    "test -w /data && test \"$(stat -c '%U:%G' /data)\" = redis:redis");
            assertThat(dataPermissions.getExitCode()).as(dataPermissions.getStderr()).isZero();
            runtimeConnections = RedisTestSupport.newConnectionFactory(runtime,
                    RedisTestSupport.featureUsername(), RedisTestSupport.featurePassword());
            StringRedisTemplate runtimeClient = new StringRedisTemplate(runtimeConnections);
            runtimeClient.afterPropertiesSet();
            assertThat(runtimeClient.opsForValue().get("feature:persisted:upgrade")).isEqualTo("retained-value");
            Long remainingTtl = runtimeClient.getExpire("feature:persisted:upgrade");
            assertThat(remainingTtl).isPositive().isLessThanOrEqualTo(3600L);
            runtimeConnections.destroy();
            runtimeConnections = null;
            runtime.stop();

            runtime = RedisTestSupport.newRuntimeContainerWithTestAdminEnvironment()
                    .withFileSystemBind(dataDirectory.toString(), "/data", BindMode.READ_WRITE);
            runtime.start();
            runtimeConnections = RedisTestSupport.newConnectionFactory(runtime,
                    RedisTestSupport.featureUsername(), RedisTestSupport.featurePassword());
            StringRedisTemplate restartedClient = new StringRedisTemplate(runtimeConnections);
            restartedClient.afterPropertiesSet();
            assertThat(restartedClient.opsForValue().get("feature:persisted:upgrade")).isEqualTo("retained-value");
            Long restartedTtl = restartedClient.getExpire("feature:persisted:upgrade");
            assertThat(restartedTtl).isPositive().isLessThanOrEqualTo(3600L);
        } finally {
            if (runtimeConnections != null) runtimeConnections.destroy();
            if (runtime != null && runtime.isRunning()) runtime.stop();
            if (legacy.isRunning()) legacy.stop();
            deleteSyntheticDataDirectory(dataDirectory);
        }
    }

    private static void deleteSyntheticDataDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
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
        assertThatThrownBy(operation::run).isInstanceOf(RuntimeException.class);
    }
}
