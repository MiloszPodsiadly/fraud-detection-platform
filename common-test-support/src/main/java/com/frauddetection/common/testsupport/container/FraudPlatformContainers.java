package com.frauddetection.common.testsupport.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public final class FraudPlatformContainers {

    private static final Network NETWORK = Network.newNetwork();

    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:3.8.0");
    private static final DockerImageName MONGODB_IMAGE = DockerImageName.parse("mongo:7.0");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2-alpine");

    private static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(KAFKA_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka");

    private static final MongoDBContainer MONGODB_CONTAINER = new MongoDBContainer(MONGODB_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("mongodb");

    private static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    private FraudPlatformContainers() {
    }

    public static void startAll() {
        if (!KAFKA_CONTAINER.isRunning()) {
            KAFKA_CONTAINER.start();
        }
        if (!MONGODB_CONTAINER.isRunning()) {
            MONGODB_CONTAINER.start();
        }
        if (!REDIS_CONTAINER.isRunning()) {
            REDIS_CONTAINER.start();
        }
    }

    public static KafkaContainer kafka() {
        return KAFKA_CONTAINER;
    }

    public static MongoDBContainer mongodb() {
        return MONGODB_CONTAINER;
    }

    public static GenericContainer<?> redis() {
        return REDIS_CONTAINER;
    }
}
