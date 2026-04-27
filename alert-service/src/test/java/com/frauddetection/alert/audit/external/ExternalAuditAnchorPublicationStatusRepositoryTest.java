package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExternalAuditAnchorPublicationStatusRepositoryTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final ExternalAuditAnchorPublicationStatusRepository repository =
            new ExternalAuditAnchorPublicationStatusRepository(mongoTemplate);

    @Test
    void shouldPersistConsistentSuccessStatus() {
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L);

        repository.recordSuccess(anchor, Instant.parse("2026-04-27T10:01:00Z"), "local-file");

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(
                org.mockito.ArgumentMatchers.any(Query.class),
                update.capture(),
                eq(ExternalAuditAnchorPublicationStatusDocument.class)
        );
        Document set = update.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(set.get("external_published")).isEqualTo(true);
        assertThat(set.get("external_published_at")).isEqualTo(Instant.parse("2026-04-27T10:01:00Z"));
        assertThat(set.get("external_sink_type")).isEqualTo("local-file");
    }

    @Test
    void shouldPersistConsistentFailureStatus() {
        AuditAnchorDocument anchor = localAnchor("local-anchor-1", 1L);

        repository.recordFailure(anchor, Instant.parse("2026-04-27T10:01:00Z"), "IO_ERROR");

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(
                org.mockito.ArgumentMatchers.any(Query.class),
                update.capture(),
                eq(ExternalAuditAnchorPublicationStatusDocument.class)
        );
        Document updateObject = update.getValue().getUpdateObject();
        Document set = updateObject.get("$set", Document.class);
        Document unset = updateObject.get("$unset", Document.class);
        assertThat(set.get("external_published")).isEqualTo(false);
        assertThat(set.get("last_external_publish_failure_reason")).isEqualTo("IO_ERROR");
        assertThat(unset).containsKeys("external_published_at", "external_sink_type");
    }

    @Test
    void shouldQueryBoundedNotPublishedStatusesByPartition() {
        repository.findNotPublished("source_service:alert-service", 999);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(ExternalAuditAnchorPublicationStatusDocument.class));
        assertThat(query.getValue().getLimit()).isEqualTo(500);
        assertThat(query.getValue().getQueryObject().toJson())
                .contains("partition_key", "source_service:alert-service", "external_published", "false");
        assertThat(query.getValue().getSortObject().toJson()).contains("chain_position", "1");
    }

    private AuditAnchorDocument localAnchor(String anchorId, long chainPosition) {
        return new AuditAnchorDocument(
                anchorId,
                Instant.parse("2026-04-27T10:00:00Z"),
                "source_service:alert-service",
                "hash-" + chainPosition,
                chainPosition,
                "SHA-256"
        );
    }
}
