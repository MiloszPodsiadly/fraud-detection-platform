package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReadAccessResultCountExtractorTest {

    private final ReadAccessResultCountExtractor extractor = new ReadAccessResultCountExtractor();

    @Test
    void shouldCountSummaryAsOneAggregateReadResponse() {
        int resultCount = extractor.resultCount(
                new FraudCaseWorkQueueSummaryResponse(46L, Instant.parse("2026-05-12T10:00:00Z")),
                ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE_SUMMARY
        );

        assertThat(resultCount).isEqualTo(1);
    }
}
