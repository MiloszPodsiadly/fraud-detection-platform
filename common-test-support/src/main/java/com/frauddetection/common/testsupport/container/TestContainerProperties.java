package com.frauddetection.common.testsupport.container;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TestContainerProperties {

    private TestContainerProperties() {
    }

    public static Map<String, String> backendProperties() {
        FraudPlatformContainers.startAll();

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.kafka.bootstrap-servers", FraudPlatformContainers.kafka().getBootstrapServers());
        properties.put("spring.data.mongodb.uri", FraudPlatformContainers.mongodb().getReplicaSetUrl());
        properties.put("spring.data.redis.host", FraudPlatformContainers.redis().getHost());
        properties.put("spring.data.redis.port", String.valueOf(FraudPlatformContainers.redis().getMappedPort(6379)));
        return properties;
    }
}
