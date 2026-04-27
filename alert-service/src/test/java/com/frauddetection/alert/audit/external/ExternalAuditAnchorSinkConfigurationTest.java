package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalAuditAnchorSinkConfigurationTest {

    private final ExternalAuditAnchorSinkConfiguration configuration = new ExternalAuditAnchorSinkConfiguration();

    @Test
    void shouldAllowLocalFileSinkForTestProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        ExternalAuditAnchorSink sink = configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                environment,
                "local-file",
                "./target/test-audit-external-anchors.jsonl",
                false
        );

        assertThat(sink.sinkType()).isEqualTo("local-file");
    }

    @Test
    void shouldRejectLocalFileSinkInProdLikeProfileByDefault() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                environment,
                "local-file",
                "./target/test-audit-external-anchors.jsonl",
                false
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local-file external audit anchor sink is not allowed");
    }

    @Test
    void shouldRejectLocalFileSinkInProdLikeProfileEvenWithOverride() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                environment,
                "local-file",
                "./target/test-audit-external-anchors.jsonl",
                true
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local-file external audit anchor sink is not allowed");
    }

    @Test
    void shouldFailClosedWhenExternalObjectStoreSinkIsSelectedButNotImplemented() {
        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                new StandardEnvironment(),
                "external-object-store",
                "./target/test-audit-external-anchors.jsonl",
                false
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not implemented yet");
    }
}
