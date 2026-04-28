package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

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
                ""
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
                ""
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
                ""
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
                ""
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
                "secret-key"
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
                ""
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires credentials");
    }

    @Test
    void shouldCreateObjectStoreSinkWhenConfigAndClientExist() {
        ObjectStoreAuditAnchorClient client = mock(ObjectStoreAuditAnchorClient.class);

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
                "secret-key"
        );

        assertThat(sink.sinkType()).isEqualTo("object-store");
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
                "secret-key"
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
