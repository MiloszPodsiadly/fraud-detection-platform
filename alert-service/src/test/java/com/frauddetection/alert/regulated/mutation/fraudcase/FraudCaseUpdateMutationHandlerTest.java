package com.frauddetection.alert.regulated.mutation.fraudcase;

import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudCaseUpdateMutationHandlerTest {

    private final FraudCaseRepository repository = mock(FraudCaseRepository.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final FraudCaseUpdateMutationHandler handler = new FraudCaseUpdateMutationHandler(repository, metrics);

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void recordsSuccessfulUpdateOnlyAfterDurableCommit() {
        arrangeStoredCase();
        beginTransaction();

        handler.update("case-1", request(), "analyst-1", context());

        verify(metrics, never()).recordFraudCaseUpdated();
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(metrics).recordFraudCaseUpdated();
    }

    @Test
    void doesNotRecordUpdateWhenTransactionRollsBackAfterRepositorySave() {
        arrangeStoredCase();
        beginTransaction();

        handler.update("case-1", request(), "analyst-1", context());
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(repository).save(any(FraudCaseDocument.class));
        verify(metrics, never()).recordFraudCaseUpdated();
    }

    @Test
    void doesNotRecordUpdateWhenLaterAuditWorkCausesRollback() {
        arrangeStoredCase();
        beginTransaction();

        assertThatThrownBy(() -> {
            try {
                handler.update("case-1", request(), "analyst-1", context());
                throw new IllegalStateException("audit write failed");
            } catch (RuntimeException failure) {
                completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
                throw failure;
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessage("audit write failed");
        verify(metrics, never()).recordFraudCaseUpdated();
    }

    @Test
    void doesNotRecordUpdateWhenOptimisticWriteFails() {
        FraudCaseDocument document = storedCase();
        when(repository.findById("case-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenThrow(new OptimisticLockingFailureException("conflict"));
        beginTransaction();

        assertThatThrownBy(() -> handler.update("case-1", request(), "analyst-1", context()))
                .isInstanceOf(OptimisticLockingFailureException.class);
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(metrics, never()).recordFraudCaseUpdated();
    }

    @Test
    void preservesImmediateObservationForNonTransactionalWrites() {
        arrangeStoredCase();

        handler.update("case-1", request(), "analyst-1", context());

        verify(metrics).recordFraudCaseUpdated();
    }

    private void arrangeStoredCase() {
        FraudCaseDocument document = storedCase();
        when(repository.findById("case-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);
    }

    private FraudCaseDocument storedCase() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setStatus(FraudCaseStatus.OPEN);
        return document;
    }

    private UpdateFraudCaseRequest request() {
        return new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "ignored-request-actor",
                "Confirmed after review",
                List.of("manual-review")
        );
    }

    private RegulatedMutationExecutionContext context() {
        return new RegulatedMutationExecutionContext("command-1");
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void completeTransaction(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
