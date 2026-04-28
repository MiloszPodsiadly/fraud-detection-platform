package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

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
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local-file external audit anchor sink is not allowed");
    }

    @Test
    void shouldRejectLegacyExternalObjectStoreSinkName() {
        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
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
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sink=object-store");
    }

    @Test
    void shouldFailClosedWhenObjectStoreConfigIsMissing() {
        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
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
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires bucket");
    }

    @Test
    void shouldFailClosedWhenObjectStoreCredentialsAreMissing() {
        assertThatThrownBy(() -> configuration.externalAuditAnchorSink(
                new ObjectMapper(),
                emptyObjectStoreClient(),
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
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup check failed");
    }

    @Test
    void shouldNotWriteStartupProbeByDefault() {
        ObjectStoreAuditAnchorClient client = mock(ObjectStoreAuditAnchorClient.class);
        when(client.listKeys("audit-bucket", "audit-anchors", 1)).thenReturn(List.of());

        configuration.externalAuditAnchorSink(
                new ObjectMapper().findAndRegisterModules(),
                objectStoreClient(client),
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
                "test-instance"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client is not configured");
    }

    private ObjectProvider<ObjectStoreAuditAnchorClient> emptyObjectStoreClient() {
        return objectStoreClient(null);
    }

    private ObjectProvider<ObjectStoreAuditAnchorClient> objectStoreClient(ObjectStoreAuditAnchorClient client) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ObjectStoreAuditAnchorClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
