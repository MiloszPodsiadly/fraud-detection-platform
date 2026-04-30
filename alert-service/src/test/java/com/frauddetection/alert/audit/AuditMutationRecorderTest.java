package com.frauddetection.alert.audit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class AuditMutationRecorderTest {

    private final AuditService auditService = mock(AuditService.class);
    private final AuditMutationRecorder recorder = new AuditMutationRecorder(auditService);

    @Test
    void shouldEmitAttemptedBeforeOperationAndSuccessAfterOperation() {
        AtomicBoolean operationExecuted = new AtomicBoolean(false);

        String result = recorder.record(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "actor-1",
                () -> {
                    operationExecuted.set(true);
                    return "ok";
                }
        );

        assertThat(result).isEqualTo("ok");
        assertThat(operationExecuted).isTrue();
        var inOrder = inOrder(auditService);
        inOrder.verify(auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "actor-1",
                AuditOutcome.ATTEMPTED,
                null
        );
        inOrder.verify(auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "actor-1",
                AuditOutcome.SUCCESS,
                null
        );
    }

    @Test
    void shouldNotExecuteOperationWhenAttemptedAuditFails() {
        AtomicBoolean operationExecuted = new AtomicBoolean(false);
        doThrow(new AuditPersistenceUnavailableException()).when(auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "actor-1",
                AuditOutcome.ATTEMPTED,
                null
        );

        assertThatThrownBy(() -> recorder.record(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "actor-1",
                () -> {
                    operationExecuted.set(true);
                    return "ok";
                }
        )).isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(operationExecuted).isFalse();
        verify(auditService, never()).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "actor-1",
                AuditOutcome.SUCCESS,
                null
        );
    }

    @Test
    void shouldEmitFailedWhenOperationFailsAndNeverEmitSuccess() {
        RuntimeException businessFailure = new IllegalStateException("write failed");

        assertThatThrownBy(() -> recorder.record(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-1",
                "actor-1",
                () -> {
                    throw businessFailure;
                }
        )).isSameAs(businessFailure);

        var inOrder = inOrder(auditService);
        inOrder.verify(auditService).audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-1",
                "actor-1",
                AuditOutcome.ATTEMPTED,
                null
        );
        inOrder.verify(auditService).audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-1",
                "actor-1",
                AuditOutcome.FAILED,
                "BUSINESS_WRITE_FAILED"
        );
        verify(auditService, never()).audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-1",
                "actor-1",
                AuditOutcome.SUCCESS,
                null
        );
    }
}
