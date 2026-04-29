package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileExternalAuditAnchorSinkTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldPublishExternalAnchorIdempotentlyWithoutOverwriting() throws Exception {
        Path path = tempDir.resolve("anchors.jsonl");
        LocalFileExternalAuditAnchorSink sink = new LocalFileExternalAuditAnchorSink(path, objectMapper);
        AuditAnchorDocument local = localAnchor("local-anchor-1", 1L, "hash-1");

        ExternalAuditAnchor first = sink.publish(ExternalAuditAnchor.from(local, sink.sinkType()));
        ExternalAuditAnchor duplicate = sink.publish(ExternalAuditAnchor.from(local, sink.sinkType()));

        assertThat(duplicate.externalAnchorId()).isEqualTo(first.externalAnchorId());
        assertThat(Files.readAllLines(path)).hasSize(1);
        assertThat(sink.latest("source_service:alert-service")).contains(first);
    }

    @Test
    void shouldRejectDuplicateLocalAnchorWithDifferentImmutableFields() {
        Path path = tempDir.resolve("anchors.jsonl");
        LocalFileExternalAuditAnchorSink sink = new LocalFileExternalAuditAnchorSink(path, objectMapper);
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-2"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("CONFLICT");
    }

    @Test
    void shouldProvideBoundedExternalReferenceForPublishedLocalFileAnchor() {
        Path path = tempDir.resolve("anchors.jsonl");
        LocalFileExternalAuditAnchorSink sink = new LocalFileExternalAuditAnchorSink(path, objectMapper);
        ExternalAuditAnchor anchor = sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));

        ExternalAnchorReference reference = sink.externalReference(anchor).orElseThrow();

        assertThat(reference.anchorId()).isEqualTo("local-anchor-1");
        assertThat(reference.externalKey()).isEqualTo("local-file:anchors.jsonl#1");
        assertThat(reference.anchorHash()).isEqualTo("hash-1");
        assertThat(reference.externalHash()).isEqualTo("hash-1");
        assertThat(reference.verifiedAt()).isNotNull();
        assertThat(reference.signatureStatus()).isNull();
    }

    private AuditAnchorDocument localAnchor(String anchorId, long chainPosition, String hash) {
        return new AuditAnchorDocument(
                anchorId,
                Instant.parse("2026-04-27T10:00:00Z"),
                "source_service:alert-service",
                hash,
                chainPosition,
                "SHA-256"
        );
    }
}
