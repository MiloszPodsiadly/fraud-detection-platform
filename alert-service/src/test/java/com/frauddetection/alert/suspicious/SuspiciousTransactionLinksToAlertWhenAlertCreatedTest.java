package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionLinksToAlertWhenAlertCreatedTest {

    @Test
    void linkedAlertCreatesAlertCreatedStatus() {
        var document = service(inMemoryRepository(), metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .projectOrUpdate(alertWorthyEvent(), "alert-1")
                .orElseThrow();

        assertThat(document.getLinkedAlertId()).isEqualTo("alert-1");
        assertThat(document.getStatus()).isEqualTo(SuspiciousTransactionStatus.ALERT_CREATED);
    }
}
