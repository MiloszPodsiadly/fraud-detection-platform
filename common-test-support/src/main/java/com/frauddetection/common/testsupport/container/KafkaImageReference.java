package com.frauddetection.common.testsupport.container;

import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.ImagePullPolicy;

/** Local Apache Kafka image built from pinned upstream Kafka and patched Temurin inputs. */
public final class KafkaImageReference {
    public static final String IMAGE = "fdp-local/kafka:4.4.0-temurin-25.0.5.1_2-r2";
    private static final ImagePullPolicy LOCAL_ONLY_PULL_POLICY = imageName -> {
        if (!IMAGE.equals(imageName.asCanonicalNameString())) {
            throw new IllegalArgumentException("Kafka test fixtures may only use the reviewed local image " + IMAGE);
        }
        return false;
    };

    private KafkaImageReference() {
    }

    public static DockerImageName dockerImageName() {
        return DockerImageName.parse(IMAGE);
    }

    public static ImagePullPolicy localOnlyPullPolicy() {
        return LOCAL_ONLY_PULL_POLICY;
    }
}
