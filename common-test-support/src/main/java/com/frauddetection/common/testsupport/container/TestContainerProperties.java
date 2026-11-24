package com.frauddetection.common.testsupport.container;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TestContainerProperties {

    private TestContainerProperties() {
    }

    public static Map<String, String> backendProperties() {
        FraudPlatformContainers.startAll();

        String mongoUri = FraudPlatformContainers.mongodb().getReplicaSetUrl();

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.kafka.bootstrap-servers", FraudPlatformContainers.kafka().getBootstrapServers());
        properties.put("spring.mongodb.uri", mongoUri);
        properties.put("spring.data.mongodb.uri", mongoUri);
        properties.put("spring.data.redis.host", FraudPlatformContainers.redis().getHost());
        properties.put("spring.data.redis.port", String.valueOf(FraudPlatformContainers.redis().getMappedPort(6379)));
        properties.put("spring.data.redis.username", FraudPlatformContainers.redisAlertUsername());
        properties.put("spring.data.redis.password", FraudPlatformContainers.redisAlertPassword());
        return properties;
    }

    public static Map<String, String> featureEnricherRedisProperties() {
        FraudPlatformContainers.startAll();
        return Map.of(
                "spring.data.redis.host", FraudPlatformContainers.redis().getHost(),
                "spring.data.redis.port", String.valueOf(FraudPlatformContainers.redis().getMappedPort(6379)),
                "spring.data.redis.username", FraudPlatformContainers.redisFeatureUsername(),
                "spring.data.redis.password", FraudPlatformContainers.redisFeaturePassword()
        );
    }
}
