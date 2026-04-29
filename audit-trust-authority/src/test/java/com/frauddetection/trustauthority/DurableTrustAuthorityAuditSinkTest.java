package com.frauddetection.trustauthority;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableTrustAuthorityAuditSinkTest {

    private static final String COLLECTION = "trust_authority_audit_events";

    @Test
    void shouldRetryOnceAfterConcurrentAppendConflictWithoutDuplicatePosition() {
        MongoTemplate mongoTemplate = mongoTemplate();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DurableTrustAuthorityAuditSink sink = new DurableTrustAuthorityAuditSink(
                mongoTemplate,
                new TrustAuthorityMetrics(registry)
        );
        TrustAuthorityAuditEvent existing = chained(event("event-existing"), null, 1L);
        when(mongoTemplate.findOne(any(Query.class), eq(TrustAuthorityAuditEvent.class), eq(COLLECTION)))
                .thenReturn(null)
                .thenReturn(existing);
        AtomicInteger attempts = new AtomicInteger();
        when(mongoTemplate.insert(any(TrustAuthorityAuditEvent.class), eq(COLLECTION)))
                .thenAnswer(invocation -> {
                    TrustAuthorityAuditEvent inserted = invocation.getArgument(0);
                    if (attempts.getAndIncrement() == 0) {
                        throw new DuplicateKeyException("chain position conflict");
                    }
                    return inserted;
                });

        sink.append(event("event-new"));

        ArgumentCaptor<TrustAuthorityAuditEvent> captor = ArgumentCaptor.forClass(TrustAuthorityAuditEvent.class);
        verify(mongoTemplate, times(2)).insert(captor.capture(), eq(COLLECTION));
        assertThat(captor.getAllValues()).extracting(TrustAuthorityAuditEvent::chainPosition)
                .containsExactly(1L, 2L);
        assertThat(captor.getAllValues().getLast().previousEventHash()).isEqualTo(existing.eventHash());
        assertThat(registry.get("audit_append_conflict_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_audit_append_conflict_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("audit_append_retry_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("trust_authority_audit_write_total").tag("status", "SUCCESS").counter().count()).isEqualTo(1.0d);
    }

    private MongoTemplate mongoTemplate() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(mongoTemplate.indexOps(COLLECTION)).thenReturn(indexOperations);
        when(indexOperations.ensureIndex(any(Index.class))).thenReturn("index");
        return mongoTemplate;
    }

    private TrustAuthorityAuditEvent chained(TrustAuthorityAuditEvent event, String previousHash, long chainPosition) {
        return event.withChain(
                previousHash,
                TrustAuthorityAuditHasher.hash(event, previousHash, chainPosition),
                chainPosition
        );
    }

    private TrustAuthorityAuditEvent event(String eventId) {
        return new TrustAuthorityAuditEvent(
                TrustAuthorityAuditEvent.CURRENT_SCHEMA_VERSION,
                eventId,
                "SIGN",
                "service=alert-service",
                "alert-service",
                "request-" + eventId,
                "AUDIT_ANCHOR",
                "hash-" + eventId,
                "key-1",
                "SUCCESS",
                null,
                Instant.parse("2026-04-29T10:00:00Z"),
                null,
                null,
                null
        );
    }
}
