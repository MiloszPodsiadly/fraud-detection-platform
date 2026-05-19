package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.IDEMPOTENCY_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.indexesByName;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionUniqueKeyUsesTransactionAndSourceEventTest {

    @Test
    void documentDeclaresUniqueTransactionAndSourceEventIndex() {
        var index = indexesByName().get(IDEMPOTENCY_INDEX);

        assertThat(index.unique()).isTrue();
        assertThat(index.def()).contains("'transactionId': 1", "'sourceEventId': 1");
    }

    @Test
    void sameTransactionDifferentSourceEventCreatesDifferentSuspiciousTransactions() {
        var repository = inMemoryRepository();
        var service = service(repository, metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var first = alertWorthyEvent();
        var second = SuspiciousTransactionTestSupport.event(
                "event-2",
                first.transactionId(),
                true,
                first.riskLevel(),
                first.scoringEvidence()
        );

        var firstDocument = service.projectOrUpdate(first, null).orElseThrow();
        var secondDocument = service.projectOrUpdate(second, null).orElseThrow();

        assertThat(firstDocument.getSuspiciousTransactionId()).isNotEqualTo(secondDocument.getSuspiciousTransactionId());
    }
}
