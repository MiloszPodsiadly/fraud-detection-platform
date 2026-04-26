package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEventReadServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuditEventReadService service = new AuditEventReadService(
            mongoTemplate,
            new AuditEventQueryParser(Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"), ZoneOffset.UTC)),
            new AlertServiceMetrics(meterRegistry)
    );

    @Test
    void shouldReadNewestFirstWithExactBoundedFiltersAndStableContract() {
        AuditEventDocument document = AuditEventDocument.from("audit-1", new AuditEvent(
                new AuditActor("admin-1", Set.of("FRAUD_OPS_ADMIN"), Set.of("audit:read")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                Instant.parse("2026-04-26T09:00:00Z"),
                "corr-1",
                AuditOutcome.SUCCESS,
                null
        ));
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(AuditEventDocument.class)))
                .thenReturn(List.of(document));

        AuditEventReadResponse response = service.readEvents(
                "SUBMIT_ANALYST_DECISION",
                "admin-1",
                "ALERT",
                "alert-1",
                "2026-04-26T00:00:00Z",
                "2026-04-26T10:00:00Z",
                50
        );

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEventDocument.class));
        String query = queryCaptor.getValue().toString();
        assertThat(query).contains("event_type", "SUBMIT_ANALYST_DECISION");
        assertThat(query).contains("actor_id", "admin-1");
        assertThat(query).contains("resource_type", "ALERT");
        assertThat(query).contains("resource_id", "alert-1");
        assertThat(query).contains("$gte", "$lte");
        assertThat(query).contains("Sort: { \"created_at\" : -1}");
        assertThat(queryCaptor.getValue().getLimit()).isEqualTo(50);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.reasonCode()).isNull();
        assertThat(response.message()).isNull();
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.limit()).isEqualTo(50);
        AuditEventResponse event = response.events().getFirst();
        assertThat(event.auditEventId()).isEqualTo("audit-1");
        assertThat(event.eventType()).isEqualTo("SUBMIT_ANALYST_DECISION");
        assertThat(event.actorId()).isEqualTo("admin-1");
        assertThat(event.actorDisplayName()).isEqualTo("admin-1");
        assertThat(event.actorRoles()).containsExactly("FRAUD_OPS_ADMIN");
        assertThat(event.resourceType()).isEqualTo("ALERT");
        assertThat(event.resourceId()).isEqualTo("alert-1");
        assertThat(event.action()).isEqualTo("SUBMIT_ANALYST_DECISION");
        assertThat(event.outcome()).isEqualTo("SUCCESS");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-04-26T09:00:00Z"));
        assertThat(event.metadataSummary().correlationId()).isEqualTo("corr-1");
    }

    @Test
    void shouldDefaultFromOnlyWindowToNowAndDefaultLimit() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(AuditEventDocument.class)))
                .thenReturn(List.of());

        AuditEventReadResponse response = service.readEvents(null, null, null, null, "2026-04-26T00:00:00Z", null, null);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEventDocument.class));
        assertThat(queryCaptor.getValue().toString()).contains("2026-04-26T10:00:00Z");
        assertThat(queryCaptor.getValue().getLimit()).isEqualTo(50);
        assertThat(response.limit()).isEqualTo(50);
    }

    @Test
    void shouldRejectInvalidQueryValues() {
        assertThatThrownBy(() -> service.readEvents("APPROVE_MODEL", null, null, null, null, null, 50))
                .isInstanceOf(InvalidAuditEventQueryException.class)
                .extracting("details")
                .asList()
                .contains("event_type: unsupported value");
        assertThatThrownBy(() -> service.readEvents(null, null, null, null, "bad", null, 50))
                .isInstanceOf(InvalidAuditEventQueryException.class);
        assertThatThrownBy(() -> service.readEvents(null, null, null, null, "2026-04-27T00:00:00Z", "2026-04-26T00:00:00Z", 50))
                .isInstanceOf(InvalidAuditEventQueryException.class);
        assertThatThrownBy(() -> service.readEvents(null, null, null, null, null, null, 101))
                .isInstanceOf(InvalidAuditEventQueryException.class);
        assertThatThrownBy(() -> service.readEvents(null, null, null, null, null, null, 0))
                .isInstanceOf(InvalidAuditEventQueryException.class);
    }

    @Test
    void shouldReturnUnavailableWhenPersistenceReadFails() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(AuditEventDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));

        AuditEventReadResponse response = service.readEvents(null, null, null, null, null, null, 25);

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.reasonCode()).isEqualTo("AUDIT_STORE_UNAVAILABLE");
        assertThat(response.message()).isEqualTo("Audit event store is currently unavailable.");
        assertThat(response.count()).isZero();
        assertThat(response.limit()).isEqualTo(25);
        assertThat(response.events()).isEmpty();
        assertThat(response.toString()).doesNotContain("mongo down", "DataAccessResourceFailureException");
        assertThat(meterRegistry.get("fraud_platform_audit_read_requests_total")
                .tag("status", "UNAVAILABLE")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldDistinguishAvailableEmptyResultFromUnavailableStore() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(AuditEventDocument.class)))
                .thenReturn(List.of());

        AuditEventReadResponse response = service.readEvents(null, null, null, null, null, null, 25);

        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.reasonCode()).isNull();
        assertThat(response.message()).isNull();
        assertThat(response.count()).isZero();
        assertThat(response.events()).isEmpty();
    }

    @Test
    void shouldBoundMetadataSummary() {
        String longReason = "x".repeat(200);
        AuditEventDocument document = AuditEventDocument.from("audit-1", new AuditEvent(
                new AuditActor("admin-1", Set.of("FRAUD_OPS_ADMIN"), Set.of()),
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                Instant.parse("2026-04-26T09:00:00Z"),
                "corr-1\nnext",
                AuditOutcome.FAILED,
                longReason
        ));

        AuditEventResponse response = AuditEventResponse.from(document);

        assertThat(response.metadataSummary().correlationId()).isEqualTo("corr-1 next");
        assertThat(response.metadataSummary().failureReason()).hasSize(120);
    }
}
