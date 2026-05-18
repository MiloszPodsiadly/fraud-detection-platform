package com.frauddetection.alert.suspicious;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SuspiciousTransactionDoesNotCreateForLowRiskNonAlertEventTest {

    @Test
    void lowRiskNonAlertEventIsSkipped() {
        SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
        var event = SuspiciousTransactionTestSupport.event("event-1", "txn-1", false, RiskLevel.LOW, List.of());

        var result = service(repository, metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .projectOrUpdate(event, null);

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }
}
