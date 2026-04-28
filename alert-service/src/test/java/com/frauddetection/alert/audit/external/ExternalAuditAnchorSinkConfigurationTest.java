package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalAuditAnchorSinkConfigurationTest {

    private final ExternalAuditAnchorSinkConfiguration configuration = new ExternalAuditAnchorSinkConfiguration();

    @Test
    void shouldAllowLocalFileSinkForTestProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        ExternalAuditAnchorSink sink = configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
                metrics(),
                environment,
                "local-file",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        );

        assertThat(sink.sinkType()).isEqualTo("local-file");
    }

    @Test
    void shouldRejectLocalFileSinkInProdLikeProfileByDefault() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
                metrics(),
                environment,
                "local-file",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local-file external audit anchor sink is not allowed");
    }

    @Test
    void shouldRejectLocalFileSinkInProdLikeProfileEvenWithOverride() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
                metrics(),
                environment,
                "local-file",
                "./target/test-audit-external-anchors.jsonl",
                true,
                "",
                "",
                "",
                "",
                "",
                "",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local-file external audit anchor sink is not allowed");
    }

    @Test
    void shouldRejectLegacyExternalObjectStoreSinkName() {
        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
                metrics(),
                new StandardEnvironment(),
                "external-object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sink=object-store");
    }

    @Test
    void shouldFailClosedWhenObjectStoreConfigIsMissing() {
        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
                metrics(),
                new StandardEnvironment(),
                "object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "",
                "audit-anchors",
                "eu-central-1",
                "",
                "access-key",
                "secret-key",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires bucket");
    }

    @Test
    void shouldFailClosedWhenObjectStoreCredentialsAreMissing() {
        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
                metrics(),
                new StandardEnvironment(),
                "object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "audit-bucket",
                "audit-anchors",
                "eu-central-1",
                "",
                "",
                "",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires credentials");
    }

    @Test
    void shouldCreateObjectStoreSinkWhenConfigAndClientExist() {
        ObjectStoreAuditAnchorClient client = mock(ObjectStoreAuditAnchorClient.class);
        when(client.listKeys("audit-bucket", "audit-anchors", 1)).thenReturn(List.of());

        ExternalAuditAnchorSink sink = configuration.externalAuditAnchorSink(
                new ObjectMapper().findAndRegisterModules(),
                objectStoreClient(client),
                metrics(),
                new StandardEnvironment(),
                "object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "audit-bucket",
                "audit-anchors",
                "eu-central-1",
                "",
                "access-key",
                "secret-key",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        );

        assertThat(sink.sinkType()).isEqualTo("object-store");
    }

    @Test
    void shouldFailClosedWhenObjectStoreStartupCheckFails() {
        ObjectStoreAuditAnchorClient client = mock(ObjectStoreAuditAnchorClient.class);
        when(client.listKeys("audit-bucket", "audit-anchors", 1)).thenThrow(new IllegalStateException("denied"));

        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper().findAndRegisterModules(),
                objectStoreClient(client),
                metrics(),
                new StandardEnvironment(),
                "object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "audit-bucket",
                "audit-anchors",
                "eu-central-1",
                "",
                "access-key",
                "secret-key",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup check failed");
    }

    @Test
    void shouldNotLeakObjectStoreCredentialsWhenStartupCheckFails() {
        ObjectStoreAuditAnchorClient client = mock(ObjectStoreAuditAnchorClient.class);
        when(client.listKeys("audit-bucket", "audit-anchors", 1))
                .thenThrow(new IllegalStateException("secret-key credential failed"));

        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper().findAndRegisterModules(),
                objectStoreClient(client),
                metrics(),
                new StandardEnvironment(),
                "object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "audit-bucket",
                "audit-anchors",
                "eu-central-1",
                "",
                "access-key",
                "secret-key",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup check failed")
                .hasMessageNotContaining("secret-key")
                .hasMessageNotContaining("access-key");
    }

    @Test
    void shouldSurfaceVerifiedImmutabilityLevelFromObjectStoreClient() {
        ObjectStoreAuditAnchorClient client = mock(ObjectStoreAuditAnchorClient.class);
        when(client.listKeys("audit-bucket", "audit-anchors", 1)).thenReturn(List.of());
        when(client.immutabilityLevel("audit-bucket", "audit-anchors")).thenReturn(ExternalImmutabilityLevel.ENFORCED);

        ExternalAuditAnchorSink sink = configuration.externalAuditAnchorSink(
                new ObjectMapper().findAndRegisterModules(),
                objectStoreClient(client),
                metrics(),
                new StandardEnvironment(),
                "object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "audit-bucket",
                "audit-anchors",
                "eu-central-1",
                "",
                "access-key",
                "secret-key",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        );

        assertThat(sink.immutabilityLevel()).isEqualTo(ExternalImmutabilityLevel.ENFORCED);
    }

    @Test
    void shouldNotWriteStartupProbeByDefault() {
        ObjectStoreAuditAnchorClient client = mock(ObjectStoreAuditAnchorClient.class);
        when(client.listKeys("audit-bucket", "audit-anchors", 1)).thenReturn(List.of());

        configuration.externalAuditAnchorSink(
                new ObjectMapper().findAndRegisterModules(),
                objectStoreClient(client),
                metrics(),
                new StandardEnvironment(),
                "object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "audit-bucket",
                "audit-anchors",
                "eu-central-1",
                "",
                "access-key",
                "secret-key",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        );

        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
                .putObjectIfAbsent(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any());
    }

    @Test
    void shouldFailClosedWhenObjectStoreClientIsMissing() {
        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
                metrics(),
                new StandardEnvironment(),
                "object-store",
                "./target/test-audit-external-anchors.jsonl",
                false,
                "audit-bucket",
                "audit-anchors",
                "",
                "https://object-store.example",
                "access-key",
                "secret-key",
                true,
                false,
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client is not configured");
    }

    private ObjectProvider<ObjectStoreAuditAnchorClient> emptyObjectStoreClient() {
        return objectStoreClient(null);
    }

    private AlertServiceMetrics metrics() {
        return new AlertServiceMetrics(new SimpleMeterRegistry());
    }

    private ObjectProvider<ObjectStoreAuditAnchorClient> objectStoreClient(ObjectStoreAuditAnchorClient client) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ObjectStoreAuditAnchorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
