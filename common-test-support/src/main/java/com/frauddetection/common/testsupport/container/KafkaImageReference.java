package com.frauddetection.common.testsupport.container;

import org.testcontainers.utility.DockerImageName;

/** Local Apache Kafka image built from pinned upstream Kafka and patched Temurin inputs. */
public final class KafkaImageReference {
    public static final String IMAGE = "apache/kafka:4.4.0-temurin-25.0.5.1_2";

    private KafkaImageReference() {
    }

    public static DockerImageName dockerImageName() {
        return DockerImageName.parse(IMAGE);
    }
}
