package com.frauddetection.common.testsupport.container;

import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaImageReferenceTest {

    @Test
    void declaresTheCustomRuntimeAsAnApacheKafkaSubstitute() {
        assertThat(KafkaImageReference.dockerImageName().asCanonicalNameString())
                .isEqualTo(KafkaImageReference.IMAGE);
        assertThatCode(() -> new KafkaContainer(KafkaImageReference.dockerImageName()))
                .doesNotThrowAnyException();
    }

    @Test
    void localImagePolicyNeverPullsAndRejectsOtherKafkaReferences() {
        assertThat(KafkaImageReference.localOnlyPullPolicy().shouldPull(KafkaImageReference.dockerImageName()))
                .isFalse();
        assertThatThrownBy(() -> KafkaImageReference.localOnlyPullPolicy()
                .shouldPull(DockerImageName.parse("fdp-local/kafka:" + "unreviewed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(KafkaImageReference.IMAGE);
    }
}
