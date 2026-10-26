package com.frauddetection.enricher.device;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MongoDeviceObservationTransactionCommitTest {
    @Test
    void unknownCommitAcknowledgementRetriesOnlyTheCommitCall() {
        ClientSession session = mock(ClientSession.class);
        MongoException unknown = unknownCommitResult();
        doThrow(unknown).doNothing().when(session).commitTransaction();

        MongoDeviceObservationStore.commitWithUnknownResultRetry(session);

        verify(session, times(2)).commitTransaction();
    }

    @Test
    void unknownCommitAcknowledgementStopsAfterBoundedRetries() {
        ClientSession session = mock(ClientSession.class);
        MongoException unknown = unknownCommitResult();
        doThrow(unknown).when(session).commitTransaction();

        assertThatThrownBy(() -> MongoDeviceObservationStore.commitWithUnknownResultRetry(session))
                .isSameAs(unknown);

        verify(session, times(3)).commitTransaction();
    }

    private static MongoException unknownCommitResult() {
        MongoException exception = new MongoException(91, "injected unknown commit result");
        exception.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
        return exception;
    }
}
