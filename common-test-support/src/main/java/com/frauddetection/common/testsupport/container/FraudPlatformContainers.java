package com.frauddetection.common.testsupport.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public final class FraudPlatformContainers {

    private static final Network NETWORK = Network.newNetwork();

    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:3.8.0@sha256:c89f315cff967322c5d2021434b32271393cb193aa7ec1d43e97341924e57069");
    private static final DockerImageName MONGODB_IMAGE = DockerImageName.parse("mongo:7.0@sha256:41e8195b2791692695e1dc9bd21cd51d0a3af05fff8ace9304a5c1acf94f1ae1");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2-alpine@sha256:2f66de68111e64c3927859f158f8e666066dae2b97d9f82770a6f37a5aa1c335");

    private static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(KAFKA_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

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

    public static Network network() {
        return NETWORK;
    }

    public static String mongodbNetworkReplicaSetUrl(String databaseName) {
        return "mongodb://mongodb:27017/" + databaseName + "?replicaSet=docker-rs";
    }

    public static String kafkaNetworkBootstrapServers() {
        return "kafka:19092";
    }

    public static String redisNetworkHost() {
        return "redis";
    }
}
