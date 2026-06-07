package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
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

class EngineIntelligenceFeedbackDatasetQueryRepositoryTest {

    @Test
    void repositoryQueryIsBounded() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(EngineIntelligenceFeedbackDocument.class)))
                .thenReturn(List.of());

        new EngineIntelligenceFeedbackDatasetQueryRepository(mongoTemplate).findBoundedBySubmittedAt(
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-02T00:00:00Z"),
                25
        );

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(EngineIntelligenceFeedbackDocument.class));
        assertThat(captor.getValue().getLimit()).isEqualTo(26);
        assertThat(captor.getValue().getSortObject().toJson())
                .contains("\"submittedAt\": -1")
                .contains("\"feedbackId\": 1");
    }

    @Test
    void noFindAll() {
        assertThat(EngineIntelligenceFeedbackDatasetQueryRepository.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("findAll");
    }
}
