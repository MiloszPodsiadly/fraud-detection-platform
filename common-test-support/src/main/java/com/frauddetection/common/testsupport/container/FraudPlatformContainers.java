package com.frauddetection.common.testsupport.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public final class FraudPlatformContainers {

    private static final Network NETWORK = Network.newNetwork();

    private static final DockerImageName KAFKA_IMAGE = KafkaImageReference.dockerImageName();
    private static final DockerImageName MONGODB_IMAGE = DockerImageName.parse("mongo:7.0@sha256:41e8195b2791692695e1dc9bd21cd51d0a3af05fff8ace9304a5c1acf94f1ae1");

    private static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(KAFKA_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static final MongoDBContainer MONGODB_CONTAINER = new MongoDBContainer(MONGODB_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("mongodb");

    private static final GenericContainer<?> REDIS_CONTAINER = RedisTestSupport.newContainer()
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

    public static String redisFeatureUsername() {
        return RedisTestSupport.featureUsername();
    }

    public static String redisFeaturePassword() {
        return RedisTestSupport.featurePassword();
    }

    public static String redisAlertUsername() {
        return RedisTestSupport.alertUsername();
    }

    public static String redisAlertPassword() {
        return RedisTestSupport.alertPassword();
    }

    public static String redisTestAdminUsername() {
        return RedisTestSupport.testAdminUsername();
    }

    public static String redisTestAdminPassword() {
        return RedisTestSupport.testAdminPassword();
    }
}
