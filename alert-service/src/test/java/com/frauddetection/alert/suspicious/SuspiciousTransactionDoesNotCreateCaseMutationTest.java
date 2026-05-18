package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionDoesNotCreateCaseMutationTest {

    @Test
    void projectionServiceHasNoFraudCaseDependency() {
        assertThat(SuspiciousTransactionProjectionService.class.getDeclaredFields())
                .extracting(field -> field.getType().getSimpleName())
                .doesNotContain("FraudCaseManagementService", "FraudCaseRepository");
    }

    @Test
    void projectionOnlyWritesSuspiciousReadModel() {
        var document = service(inMemoryRepository(), metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .projectOrUpdate(alertWorthyEvent(), null)
                .orElseThrow();

        assertThat(document.getStatus()).isEqualTo(SuspiciousTransactionStatus.NEW);
    }
}
