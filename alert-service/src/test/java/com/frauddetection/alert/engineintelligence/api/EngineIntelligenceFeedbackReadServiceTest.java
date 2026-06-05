package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EngineIntelligenceFeedbackReadServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-04T10:15:30Z");

    private final ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
    private final EngineIntelligenceFeedbackRepository feedbackRepository = mock(EngineIntelligenceFeedbackRepository.class);
    private final EngineIntelligenceFeedbackReadModelMapper mapper = new EngineIntelligenceFeedbackReadModelMapper();
    private final EngineIntelligenceFeedbackReadPolicy readPolicy = new EngineIntelligenceFeedbackReadPolicy();
    private final EngineIntelligenceFeedbackReadService service = new EngineIntelligenceFeedbackReadService(
            scoredTransactionRepository,
            feedbackRepository,
            mapper,
            readPolicy
    );

    @Test
    void returnsFeedbackForTransaction() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(feedbackRepository.findByTransactionId(eq("txn-1"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(document("feedback-1", NOW)));

        EngineIntelligenceFeedbackReadModel response = service.read("txn-1", 25);

        assertThat(response.transactionId()).isEqualTo("txn-1");
        assertThat(response.feedback()).singleElement()
                .satisfies(entry -> assertThat(entry.feedbackId()).isEqualTo("feedback-1"));
        assertThat(response.page()).isEqualTo(new EngineIntelligenceFeedbackPage(25, false));
    }

    @Test
    void returnsEmptyListWhenNoFeedbackExists() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(feedbackRepository.findByTransactionId(eq("txn-1"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of());

        EngineIntelligenceFeedbackReadModel response = service.read("txn-1", 25);

        assertThat(response.feedback()).isEmpty();
        assertThat(response.page()).isEqualTo(new EngineIntelligenceFeedbackPage(25, false));
    }

    @Test
    void returns404WhenTransactionDoesNotExist() {
        when(scoredTransactionRepository.existsById("txn-missing")).thenReturn(false);

        assertThatThrownBy(() -> service.read("txn-missing", 25))
                .isInstanceOf(EngineIntelligenceScoredTransactionNotFoundException.class);

        verify(feedbackRepository, never()).findByTransactionId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidTransactionIdReturnsBoundedNotFoundWithoutRepositoryLookup() {
        assertThatThrownBy(() -> service.read("txn/with-token-secret", 25))
                .isInstanceOf(EngineIntelligenceScoredTransactionNotFoundException.class)
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("secret");

        verifyNoInteractions(scoredTransactionRepository, feedbackRepository);
    }

    @Test
    void requestsOneExtraForHasMore() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(feedbackRepository.findByTransactionId(eq("txn-1"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of());

        service.read("txn-1", 25);

        Pageable pageable = capturedPageable();
        assertThat(pageable.getPageSize()).isEqualTo(26);
    }

    @Test
    void returnsAtMostLimitEntriesAndHasMoreTrueWhenMoreThanLimit() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(feedbackRepository.findByTransactionId(eq("txn-1"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(
                        document("feedback-1", NOW),
                        document("feedback-2", NOW.minusSeconds(1)),
                        document("feedback-3", NOW.minusSeconds(2))
                ));

        EngineIntelligenceFeedbackReadModel response = service.read("txn-1", 2);

        assertThat(response.feedback()).extracting(EngineIntelligenceFeedbackEntryReadModel::feedbackId)
                .containsExactly("feedback-1", "feedback-2");
        assertThat(response.page()).isEqualTo(new EngineIntelligenceFeedbackPage(2, true));
    }

    @Test
    void sortIsSubmittedAtDescendingWithFeedbackIdTieBreakAndNoFindAll() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(feedbackRepository.findByTransactionId(eq("txn-1"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of());

        service.read("txn-1", 25);

        Sort sort = capturedPageable().getSort();
        assertThat(sort.getOrderFor("submittedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("feedbackId").getDirection()).isEqualTo(Sort.Direction.ASC);
        verify(feedbackRepository, never()).findAll();
    }

    @Test
    void repositoryFailureThrowsStableUnavailableException() {
        when(scoredTransactionRepository.existsById("txn-store-failure")).thenReturn(true);
        when(feedbackRepository.findByTransactionId(eq("txn-store-failure"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenThrow(new IllegalStateException("raw mongo endpoint token secret"));

        assertThatThrownBy(() -> service.read("txn-store-failure", 25))
                .isInstanceOf(EngineIntelligenceFeedbackReadUnavailableException.class)
                .hasMessageNotContaining("mongo")
                .hasMessageNotContaining("endpoint")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("secret");
    }

    @Test
    void corruptedFeedbackWithRawEvidenceReasonCodeReturns503WithoutLeak() {
        assertCorruptedDocumentFailsClosed(document("feedback-1", NOW, List.of("rawEvidence")));
    }

    @Test
    void corruptedFeedbackWithTokenSecretStacktraceReasonCodeReturns503WithoutLeak() {
        assertCorruptedDocumentFailsClosed(document("feedback-1", NOW, List.of("token-secret-stacktrace")));
    }

    @Test
    void corruptedFeedbackWithNullSubmittedAtReturns503() {
        assertCorruptedDocumentFailsClosed(document("feedback-1", null));
    }

    @Test
    void corruptedFeedbackWithBlankFeedbackIdReturns503() {
        assertCorruptedDocumentFailsClosed(document("   ", NOW));
    }

    @Test
    void corruptedFeedbackWithNullFeedbackTypeReturns503() {
        assertCorruptedDocumentFailsClosed(new EngineIntelligenceFeedbackDocument(
                "feedback-1",
                "txn-1",
                true,
                null,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                List.of("HIGH_VELOCITY"),
                "analyst-1",
                NOW,
                "correlation-1",
                "idempotency-hash-1",
                "payload-hash-1",
                NOW
        ));
    }

    @Test
    void corruptedFeedbackWithNullUsefulnessReturns503() {
        assertCorruptedDocumentFailsClosed(new EngineIntelligenceFeedbackDocument(
                "feedback-1",
                "txn-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                null,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                List.of("HIGH_VELOCITY"),
                "analyst-1",
                NOW,
                "correlation-1",
                "idempotency-hash-1",
                "payload-hash-1",
                NOW
        ));
    }

    @Test
    void corruptedFeedbackWithNullAccuracyAssessmentReturns503() {
        assertCorruptedDocumentFailsClosed(new EngineIntelligenceFeedbackDocument(
                "feedback-1",
                "txn-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                null,
                List.of("HIGH_VELOCITY"),
                "analyst-1",
                NOW,
                "correlation-1",
                "idempotency-hash-1",
                "payload-hash-1",
                NOW
        ));
    }

    @Test
    void corruptedFeedbackWithOverLimitSelectedReasonCodesReturns503() {
        assertCorruptedDocumentFailsClosed(document(
                "feedback-1",
                NOW,
                List.of("A", "B", "C", "D", "E", "F")
        ));
    }

    @Test
    void corruptedFeedbackWithAccuracyAssessmentAsReasonCodeReturns503() {
        assertCorruptedDocumentFailsClosed(document(
                "feedback-1",
                NOW,
                List.of("SIGNALS_LOOK_CORRECT")
        ));
    }

    @Test
    void corruptedFeedbackDoesNotReturnPartialResponse() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(feedbackRepository.findByTransactionId(eq("txn-1"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(
                        document("feedback-valid", NOW),
                        document("feedback-corrupt", NOW.minusSeconds(1), List.of("rawEvidence"))
                ));

        assertThatThrownBy(() -> service.read("txn-1", 25))
                .isInstanceOf(EngineIntelligenceFeedbackReadUnavailableException.class);
    }

    private void assertCorruptedDocumentFailsClosed(EngineIntelligenceFeedbackDocument document) {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(feedbackRepository.findByTransactionId(eq("txn-1"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(document));

        assertThatThrownBy(() -> service.read("txn-1", 25))
                .isInstanceOf(EngineIntelligenceFeedbackReadUnavailableException.class)
                .hasMessageNotContaining("rawEvidence")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("stacktrace");
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(feedbackRepository).findByTransactionId(eq("txn-1"), pageable.capture());
        return pageable.getValue();
    }

    private EngineIntelligenceFeedbackDocument document(String feedbackId, Instant submittedAt) {
        return document(feedbackId, submittedAt, List.of("HIGH_VELOCITY"));
    }

    private EngineIntelligenceFeedbackDocument document(String feedbackId, Instant submittedAt, List<String> reasonCodes) {
        return new EngineIntelligenceFeedbackDocument(
                feedbackId,
                "txn-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                reasonCodes,
                "analyst-1",
                submittedAt,
                "correlation-1",
                "idempotency-hash-1",
                "payload-hash-1",
                submittedAt
        );
    }
}
