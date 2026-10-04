package com.frauddetection.alert.suspicious;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.CURRENT_OWNERSHIP_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.indexesByName;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionCurrentProjectionOwnershipTest {

    @Test
    void documentDeclaresUniqueTransactionIndex() {
        var index = indexesByName().get(CURRENT_OWNERSHIP_INDEX);

        assertThat(index.unique()).isTrue();
        assertThat(keys(CURRENT_OWNERSHIP_INDEX)).isEqualTo(new LinkedHashMap<>() {{
            put("transactionId", 1);
        }});
    }

    @Test
    void laterAcceptedOccurrenceUpdatesTheTransactionScopedProjection() {
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

        assertThat(secondDocument.getSuspiciousTransactionId()).isEqualTo(firstDocument.getSuspiciousTransactionId());
        assertThat(secondDocument.getSourceEventId()).isEqualTo("event-2");
    }

    @Test
    void laterNonAlertWorthyOccurrenceRemovesTheCurrentSuspiciousProjection() {
        var repository = inMemoryRepository();
        var service = service(repository, metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var earlierHigh = alertWorthyEvent();
        var laterLow = SuspiciousTransactionTestSupport.event(
                "event-2",
                earlierHigh.transactionId(),
                false,
                RiskLevel.LOW,
                List.of()
        );

        service.projectOrUpdate(earlierHigh, "alert-1");

        assertThat(service.projectOrUpdate(laterLow, null)).isEmpty();
        assertThat(repository.findByTransactionId(earlierHigh.transactionId())).isEmpty();
    }
}
