package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackDatasetCandidateStoreTest {

    @Test
    void storeFetchesMaxRecordsPlusOneForTruncationDetection() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(FraudFeedbackRecord.class)))
                .thenReturn(List.of());

        new FeedbackDatasetCandidateStore(mongoTemplate).findBoundedByCreatedAt(
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-02T00:00:00Z"),
                25
        );

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(FraudFeedbackRecord.class));
        assertThat(captor.getValue().getLimit()).isEqualTo(26);
        assertThat(captor.getValue().getSortObject().toJson())
                .contains("\"createdAt\": 1")
                .contains("\"feedbackId\": 1");
        assertThat(captor.getValue().getQueryObject().toString())
                .contains("CONFIRMED_FRAUD")
                .contains("CONFIRMED_LEGITIMATE")
                .contains("INCONCLUSIVE")
                .contains("NEEDS_MORE_INFO")
                .contains("createdAt");
    }

    @Test
    void noFindAllMethodExistsOnDatasetStore() {
        assertThat(FeedbackDatasetCandidateStore.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("findAll");
    }
}
