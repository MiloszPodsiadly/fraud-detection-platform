package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationClaimServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final RegulatedMutationClaimService service = new RegulatedMutationClaimService(
            mongoTemplate,
            Duration.ofSeconds(30)
    );

    @Test
    void newCommandIsClaimedWhenMongoReturnsDocument() {
        RegulatedMutationCommandDocument claimed = document(RegulatedMutationExecutionStatus.NEW, null);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(claimed);

        Optional<RegulatedMutationCommandDocument> result = service.claim(command(), "idem-1");

        assertThat(result).containsSame(claimed);
    }

    @Test
    void nullCommandRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claim(null, "idem-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Regulated mutation command is required.");

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void nullIdempotencyKeyRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claim(command(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Regulated mutation idempotency key is required.");

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void blankIdempotencyKeyRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claim(command(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Regulated mutation idempotency key is required.");

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void validClaimStillBuildsSameFindAndModifyQuery() {
        service.claim(command(), "idem-1");

        Query query = capturedQuery();
        String queryJson = query.getQueryObject().toString();
        assertThat(queryJson).contains("idempotency_key=idem-1");
        assertThat(queryJson).contains("request_hash=request-hash-1");
        assertThat(queryJson).contains("execution_status=NEW");
        assertThat(queryJson).contains("execution_status=PROCESSING");
        assertThat(queryJson).contains("lease_expires_at");
    }

    @Test
    void expiredProcessingCommandIsClaimableByQuery() {
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(document(RegulatedMutationExecutionStatus.PROCESSING, Instant.now().minusSeconds(1)));

        service.claim(command(), "idem-1");

        Query query = capturedQuery();
        String queryJson = query.getQueryObject().toString();
        assertThat(queryJson).contains("idempotency_key=idem-1");
        assertThat(queryJson).contains("request_hash=request-hash-1");
        assertThat(queryJson).contains("execution_status=NEW");
        assertThat(queryJson).contains("execution_status=PROCESSING");
        assertThat(queryJson).contains("lease_expires_at");
    }

    @Test
    void activeProcessingCommandIsNotClaimedWhenMongoReturnsNoDocument() {
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(null);

        Optional<RegulatedMutationCommandDocument> result = service.claim(command(), "idem-1");

        assertThat(result).isEmpty();
    }

    @Test
    void claimDoesNotClaimActiveProcessingLease() {
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(null);

        Optional<RegulatedMutationCommandDocument> result = service.claim(command(), "idem-1");

        assertThat(result).isEmpty();
        String queryJson = capturedQuery().getQueryObject().toString();
        assertThat(queryJson).contains("execution_status=NEW");
        assertThat(queryJson).contains("execution_status=PROCESSING");
        assertThat(queryJson).contains("lease_expires_at");
    }

    @Test
    void claimAllowsExpiredProcessingLeaseByQuery() {
        service.claim(command(), "idem-1");

        String queryJson = capturedQuery().getQueryObject().toString();

        assertThat(queryJson).contains("execution_status=PROCESSING");
        assertThat(queryJson).contains("lease_expires_at");
    }

    @Test
    void claimQueryDoesNotPretendToFenceLaterWrites() {
        service.claim(command(), "idem-1");

        String queryJson = capturedQuery().getQueryObject().toString();
        Document update = setDocument();

        assertThat(queryJson).contains("idempotency_key=idem-1");
        assertThat(queryJson).contains("request_hash=request-hash-1");
        assertThat(queryJson).doesNotContain("lease_owner=");
        assertThat(update).containsKeys("execution_status", "lease_owner", "lease_expires_at");
        assertThat(update).doesNotContainKeys("state", "public_status", "response_snapshot", "success_audit_recorded");
    }

    @Test
    void claimSetsExecutionStatusProcessing() {
        service.claim(command(), "idem-1");

        assertThat(setDocument().get("execution_status")).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
    }

    @Test
    void claimSetsLeaseOwner() {
        service.claim(command(), "idem-1");

        assertThat(setDocument().getString("lease_owner")).isNotBlank();
    }

    @Test
    void claimSetsLeaseExpiration() {
        Instant before = Instant.now();

        service.claim(command(), "idem-1");

        assertThat((Instant) setDocument().get("lease_expires_at")).isAfter(before);
    }

    @Test
    void claimUpdatesHeartbeatAndUpdatedAt() {
        service.claim(command(), "idem-1");

        Document set = setDocument();
        assertThat((Instant) set.get("last_heartbeat_at")).isNotNull();
        assertThat((Instant) set.get("updated_at")).isNotNull();
    }

    @Test
    void claimIncrementsAttemptCount() {
        service.claim(command(), "idem-1");

        assertThat(incDocument().get("attempt_count")).isEqualTo(1);
    }

    @Test
    void claimRequiresMatchingIdempotencyKeyAndRequestHash() {
        service.claim(command(), "idem-1");

        String queryJson = capturedQuery().getQueryObject().toString();
        assertThat(queryJson).contains("idempotency_key=idem-1");
        assertThat(queryJson).contains("request_hash=request-hash-1");
    }

    @Test
    void claimHasNoBusinessMutationOrAuditPath() {
        service.claim(command(), "idem-1");

        verify(mongoTemplate).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    private Query capturedQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndModify(
                captor.capture(),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
        return captor.getValue();
    }

    private Document setDocument() {
        return updateDocument("$set");
    }

    private Document incDocument() {
        return updateDocument("$inc");
    }

    private Document updateDocument(String key) {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(
                any(Query.class),
                captor.capture(),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
        return (Document) captor.getValue().getUpdateObject().get(key);
    }

    private RegulatedMutationCommandDocument document(
            RegulatedMutationExecutionStatus status,
            Instant leaseExpiresAt
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setExecutionStatus(status);
        document.setLeaseExpiresAt(leaseExpiresAt);
        return document;
    }

    private RegulatedMutationCommand<String, String> command() {
        return new RegulatedMutationCommand<>(
                "idem-1",
                "principal-7",
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                "request-hash-1",
                context -> "ok",
                (result, state) -> state.name(),
                response -> null,
                snapshot -> "ok",
                state -> state.name()
        );
    }
}
