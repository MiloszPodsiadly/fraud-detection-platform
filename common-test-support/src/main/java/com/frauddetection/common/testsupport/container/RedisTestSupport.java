package com.frauddetection.common.testsupport.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Runtime-equivalent Redis fixture with a separately supplied test-only admin ACL. */
public final class RedisTestSupport {

    public static final String IMAGE = "redis:8.12.0-alpine@sha256:ff55e25203f915f2fe9cc1428e6da969fec257dd6e6feb71a34fd0cf3a86b289";
    public static final String FEATURE_USERNAME = "feature-enricher";
    public static final String ALERT_USERNAME = "alert-service";
    public static final String TEST_ADMIN_USERNAME = "test-admin";

    private static final String FEATURE_PASSWORD = randomPassword();
    private static final String ALERT_PASSWORD = randomPassword();
    private static final String TEST_ADMIN_PASSWORD = randomPassword();

    private RedisTestSupport() {
    }

    public static GenericContainer<?> newContainer() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(6379)
                .withCopyFileToContainer(MountableFile.forHostPath(findDeploymentFile("redis-acl-users.sh").toString()), "/opt/redis-acl-users.sh")
                .withCopyFileToContainer(MountableFile.forHostPath(findDeploymentFile("redis-test-entrypoint.sh").toString()), "/opt/redis-test-entrypoint.sh")
                .withEnv("REDIS_FEATURE_ENRICHER_PASSWORD", FEATURE_PASSWORD)
                .withEnv("REDIS_ALERT_PASSWORD", ALERT_PASSWORD)
                .withEnv("REDIS_TEST_ADMIN_PASSWORD", TEST_ADMIN_PASSWORD)
                .withCommand("/bin/sh", "/opt/redis-test-entrypoint.sh");
    }

    public static GenericContainer<?> newRuntimeContainerWithTestAdminEnvironment() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(6379)
                .withCopyFileToContainer(MountableFile.forHostPath(findDeploymentFile("redis-acl-users.sh").toString()), "/opt/redis-acl-users.sh")
                .withCopyFileToContainer(MountableFile.forHostPath(findDeploymentFile("redis-entrypoint.sh").toString()), "/opt/redis-entrypoint.sh")
                .withEnv("REDIS_FEATURE_ENRICHER_PASSWORD", FEATURE_PASSWORD)
                .withEnv("REDIS_ALERT_PASSWORD", ALERT_PASSWORD)
                .withEnv("REDIS_TEST_ADMIN_PASSWORD", TEST_ADMIN_PASSWORD)
                .withCommand("/bin/sh", "/opt/redis-entrypoint.sh");
    }

    public static LettuceConnectionFactory newConnectionFactory(
            GenericContainer<?> container,
            String username,
            String password
    ) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                container.getHost(), container.getMappedPort(6379));
        configuration.setUsername(username);
        configuration.setPassword(RedisPassword.of(password));
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        return connectionFactory;
    }

    public static StringRedisTemplate newTemplate(
            GenericContainer<?> container,
            String username,
            String password
    ) {
        LettuceConnectionFactory connectionFactory = newConnectionFactory(container, username, password);
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    public static String featureUsername() {
        return FEATURE_USERNAME;
    }

    public static String featurePassword() {
        return FEATURE_PASSWORD;
    }

    public static String alertUsername() {
        return ALERT_USERNAME;
    }

    public static String alertPassword() {
        return ALERT_PASSWORD;
    }

    public static String testAdminUsername() {
        return TEST_ADMIN_USERNAME;
    }

    public static String testAdminPassword() {
        return TEST_ADMIN_PASSWORD;
    }

    public static StringRedisTemplate newAdminTemplate(GenericContainer<?> container) {
        return newTemplate(container, TEST_ADMIN_USERNAME, TEST_ADMIN_PASSWORD);
    }

    public static StringRedisTemplate newFeatureTemplate(GenericContainer<?> container) {
        return newTemplate(container, FEATURE_USERNAME, FEATURE_PASSWORD);
    }

    public static StringRedisTemplate newAlertTemplate(GenericContainer<?> container) {
        return newTemplate(container, ALERT_USERNAME, ALERT_PASSWORD);
    }

    public static void flushAll(GenericContainer<?> container) {
        withAdmin(container, template -> template.getConnectionFactory().getConnection().serverCommands().flushAll());
    }

    public static void flushDb(GenericContainer<?> container) {
        withAdmin(container, template -> template.getConnectionFactory().getConnection().serverCommands().flushDb());
    }

    public static Set<String> keys(GenericContainer<?> container, String pattern) {
        StringRedisTemplate template = newAdminTemplate(container);
        try {
            return template.keys(pattern);
        } finally {
            close(template);
        }
    }

    public static void delete(GenericContainer<?> container, Collection<String> keys) {
        withAdmin(container, template -> template.delete(keys));
    }

    public static Properties info(GenericContainer<?> container, String section) {
        StringRedisTemplate template = newAdminTemplate(container);
        try (var connection = template.getConnectionFactory().getConnection()) {
            return connection.serverCommands().info(section);
        } finally {
            close(template);
        }
    }

    private static void withAdmin(GenericContainer<?> container, java.util.function.Consumer<StringRedisTemplate> action) {
        StringRedisTemplate template = newAdminTemplate(container);
        try {
            action.accept(template);
        } finally {
            close(template);
        }
    }

    public static void close(StringRedisTemplate template) {
        if (template != null && template.getConnectionFactory() instanceof LettuceConnectionFactory connectionFactory) {
            connectionFactory.destroy();
        }
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Path findDeploymentFile(String filename) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("deployment").resolve(filename);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate deployment/" + filename + " from the test working directory.");
    }
}
