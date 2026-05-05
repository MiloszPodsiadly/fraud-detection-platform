package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationLeaseRenewalServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-05T10:00:00Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final RegulatedMutationLeaseRenewalPolicy policy =
            new RegulatedMutationLeaseRenewalPolicy(Duration.ofSeconds(30), Duration.ofMinutes(2), 3);
    private final RegulatedMutationLeaseRenewalService service = new RegulatedMutationLeaseRenewalService(
            mongoTemplate,
            policy,
            new AlertServiceMetrics(new SimpleMeterRegistry()),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void renewalUsesConditionalOwnerFencedMongoUpdateOnlyForLeaseMetadata() {
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(document());
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.renew(token(), Duration.ofSeconds(40), "unit");

        String query = capturedQuery().getQueryObject().toString();
        assertThat(query)
                .contains("_id=command-1")
                .contains("lease_owner=owner-1")
                .contains("lease_expires_at")
                .contains("execution_status=PROCESSING")
                .contains("state=REQUESTED")
                .contains("mutation_model_version");

        Document set = updateDocument("$set");
        Document inc = updateDocument("$inc");
        assertThat(set).containsKeys(
                "lease_expires_at",
                "last_heartbeat_at",
                "last_lease_renewed_at",
                "lease_budget_started_at",
                "updated_at"
        );
        assertThat(inc.get("lease_renewal_count")).isEqualTo(1);
        assertThat(set).doesNotContainKeys(
                "idempotency_key",
                "request_hash",
                "actor_id",
                "resource_id",
                "action",
                "resource_type",
                "response_snapshot",
                "outbox_event_id",
                "local_commit_marker",
                "success_audit_id",
                "success_audit_recorded",
                "public_status",
                "state",
                "execution_status"
        );
    }

    private Query capturedQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(
                captor.capture(),
                any(Update.class),
                eq(RegulatedMutationCommandDocument.class)
        );
        return captor.getValue();
    }

    private Document updateDocument(String key) {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                any(Query.class),
                captor.capture(),
                eq(RegulatedMutationCommandDocument.class)
        );
        return (Document) captor.getValue().getUpdateObject().get(key);
    }

    private RegulatedMutationCommandDocument document() {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-1");
        document.setLeaseOwner("owner-1");
        document.setLeaseExpiresAt(NOW.plusSeconds(5));
        document.setLeaseBudgetStartedAt(NOW);
        document.setMutationModelVersion(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        document.setState(RegulatedMutationState.REQUESTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        return document;
    }

    private RegulatedMutationClaimToken token() {
        return new RegulatedMutationClaimToken(
                "command-1",
                "owner-1",
                NOW.plusSeconds(5),
                NOW,
                1,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
    }
}
