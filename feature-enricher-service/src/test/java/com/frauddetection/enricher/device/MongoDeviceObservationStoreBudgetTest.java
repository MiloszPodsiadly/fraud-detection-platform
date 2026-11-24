package com.frauddetection.enricher.device;

import com.frauddetection.common.events.source.MongoOperationBudget;
import com.frauddetection.enricher.graph.GraphReadReason;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoOperationTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MongoDeviceObservationStoreBudgetTest {
    @Test
    void classifiesMongoOperationTimeoutAsDeadlineBeforeMonotonicBudgetExpires() {
        MongoOperationBudget budget = MongoOperationBudget.starting(Duration.ofSeconds(1));

        GraphReadReason reason = MongoDeviceObservationStore.graphOccurrenceFailureReason(
                new MongoOperationTimeoutException("operation timed out"), budget);

        assertThat(reason).isEqualTo(GraphReadReason.DEADLINE_EXCEEDED);
    }

    @Test
    void classifiesServerExecutionTimeoutAsDeadline() {
        MongoOperationBudget budget = MongoOperationBudget.starting(Duration.ofSeconds(1));

        GraphReadReason reason = MongoDeviceObservationStore.graphOccurrenceFailureReason(
                new MongoExecutionTimeoutException(50, "maximum execution time exceeded"), budget);

        assertThat(reason).isEqualTo(GraphReadReason.DEADLINE_EXCEEDED);
    }

    @Test
    void recognizesDeadlineFromBudgetChecks() {
        assertThat(MongoOperationBudget.isDeadlineFailure(
                new IllegalStateException("MONGO_OPERATION_DEADLINE_EXCEEDED"))).isTrue();
    }

    @Test
    void preservesOwnerUnavailableForUnrelatedMongoFailures() {
        MongoOperationBudget budget = MongoOperationBudget.starting(Duration.ofSeconds(1));

        GraphReadReason reason = MongoDeviceObservationStore.graphOccurrenceFailureReason(
                new MongoException(91, "database unavailable"), budget);

        assertThat(reason).isEqualTo(GraphReadReason.OWNER_UNAVAILABLE);
    }

    @Test
    void doesNotTreatTimeoutAsDeadlineWithoutAnOperationBudget() {
        GraphReadReason reason = MongoDeviceObservationStore.graphOccurrenceFailureReason(
                new MongoOperationTimeoutException("operation timed out"), null);

        assertThat(reason).isEqualTo(GraphReadReason.OWNER_UNAVAILABLE);
    }
}
