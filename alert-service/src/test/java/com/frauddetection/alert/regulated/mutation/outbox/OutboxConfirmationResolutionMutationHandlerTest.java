package com.frauddetection.alert.regulated.mutation.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.outbox.OutboxConfirmationResolution;
import com.frauddetection.alert.outbox.OutboxConfirmationResolutionRequest;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxConfirmationResolutionMutationHandlerTest {

    @Test
    void shouldRequireDistinctSecondActorForBankModeDualControl() {
        Fixture fixture = fixture(true, true);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));
        when(fixture.repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument requested = fixture.handler.resolve("event-1", request(), "ops-1");

        assertThat(requested.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThat(requested.isResolutionPending()).isTrue();
        assertThat(requested.getResolutionControlMode()).isEqualTo("DUAL_CONTROL_REQUESTED");

        assertThatThrownBy(() -> fixture.handler.resolve("event-1", request(), "ops-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("distinct actor");

        TransactionalOutboxRecordDocument approved = fixture.handler.resolve("event-1", request(), "ops-2");

        assertThat(approved.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(approved.isResolutionPending()).isFalse();
        assertThat(approved.getResolutionControlMode()).isEqualTo("DUAL_CONTROL_APPROVED");
        assertThat(approved.getResolutionApprovedBy()).isEqualTo("ops-2");
    }

    @Test
    void shouldUseExplicitSingleControlAttestationOutsideBankMode() {
        Fixture fixture = fixture(false, false);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));
        when(fixture.repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument resolved = fixture.handler.resolve("event-1", request(), "ops-1");

        assertThat(resolved.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(resolved.getResolutionControlMode()).isEqualTo("SINGLE_CONTROL_OPERATOR_ATTESTED");
    }

    @Test
    void shouldRejectSingleControlPublishedResolutionInBankMode() {
        Fixture fixture = fixture(true, false);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> fixture.handler.resolve("event-1", request(), "ops-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("bank mode requires dual-control");
    }

    private Fixture fixture(boolean bankMode, boolean dualControl) {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        return new Fixture(
                repository,
                mongoTemplate,
                new OutboxConfirmationResolutionMutationHandler(repository, mongoTemplate, bankMode, dualControl)
        );
    }

    private TransactionalOutboxRecordDocument record() {
        TransactionalOutboxRecordDocument document = new TransactionalOutboxRecordDocument();
        document.setEventId("event-1");
        document.setResourceId("alert-1");
        document.setStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        return document;
    }

    private OutboxConfirmationResolutionRequest request() {
        return new OutboxConfirmationResolutionRequest(
                OutboxConfirmationResolution.PUBLISHED,
                "broker offset verified",
                new ResolutionEvidenceReference(
                        ResolutionEvidenceType.BROKER_OFFSET,
                        "topic=fraud-decisions,partition=0,offset=42",
                        Instant.parse("2026-05-02T10:00:00Z"),
                        "ops"
                )
        );
    }

    private record Fixture(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxConfirmationResolutionMutationHandler handler
    ) {
    }
}
