package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionCreationFromScoredEventTest {

    @Test
    void createsReadModelForAlertWorthyScoredEvent() {
        var repository = inMemoryRepository();
        var event = alertWorthyEvent();

        var document = service(repository, metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .projectOrUpdate(event, null)
                .orElseThrow();

        assertThat(document.getTransactionId()).isEqualTo(event.transactionId());
        assertThat(document.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(document.getStatus()).isEqualTo(SuspiciousTransactionStatus.NEW);
        assertThat(document.getDetectionSource()).isEqualTo(DetectionSource.RULE_ENGINE);
        assertThat(document.getDetectedAt()).isEqualTo(event.inferenceTimestamp());
    }
}
