package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.InvalidEngineIntelligenceFeedbackRequestException;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineIntelligenceFeedbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-03T10:15:30Z");

    private final ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
    private final EngineIntelligenceFeedbackRepository feedbackRepository = mock(EngineIntelligenceFeedbackRepository.class);
    private final SharedIdempotencyKeyPolicy idempotencyKeyPolicy = new SharedIdempotencyKeyPolicy();
    private final CurrentAnalystUser currentAnalystUser = mock(CurrentAnalystUser.class);
    private final AuditService auditService = mock(AuditService.class);
    private final EngineIntelligenceFeedbackService service = new EngineIntelligenceFeedbackService(
            scoredTransactionRepository,
            feedbackRepository,
            idempotencyKeyPolicy,
            currentAnalystUser,
            auditService,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void feedbackSubmissionIsPersistedWithServerGeneratedFieldsAndAudit() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(currentAnalystUser.get()).thenReturn(Optional.of(analyst("analyst-1")));
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EngineIntelligenceFeedbackResponse response = service.submit("txn-1", validRequest(), "feedback-key-1");

        assertThat(response.operationStatus()).isEqualTo("CREATED");
        assertThat(response.feedbackId()).isNotBlank();
        assertThat(response.transactionId()).isEqualTo("txn-1");
        assertThat(response.submittedBy()).isEqualTo("analyst-1");
        assertThat(response.submittedAt()).isEqualTo(NOW);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.correlationId()).isNotBlank();

        ArgumentCaptor<EngineIntelligenceFeedbackDocument> saved = ArgumentCaptor.forClass(EngineIntelligenceFeedbackDocument.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getFeedbackType()).isEqualTo(EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS);
        assertThat(saved.getValue().getIdempotencyKeyHash()).isNotEqualTo("feedback-key-1");

        ArgumentCaptor<AuditEventMetadataSummary> metadata = ArgumentCaptor.forClass(AuditEventMetadataSummary.class);
        verify(auditService).audit(
                eq(AuditAction.SUBMIT_ENGINE_INTELLIGENCE_FEEDBACK),
                eq(AuditResourceType.ENGINE_INTELLIGENCE_FEEDBACK),
                eq(response.feedbackId()),
                eq(response.correlationId()),
                eq("analyst-1"),
                any(),
                eq(null),
                metadata.capture(),
                eq(response.feedbackId())
        );
        assertThat(metadata.getValue().filtersSummary())
                .contains("transaction_id=txn-1")
                .contains("feedback_type=ENGINE_INTELLIGENCE_USEFULNESS")
                .doesNotContain("payload", "token", "stacktrace", "endpoint");
    }

    @Test
    void duplicateSubmissionWithSameIdempotencyKeyReturnsExistingFeedbackWithoutNewRecord() {
        EngineIntelligenceFeedbackDocument existing = existingDocument();
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(currentAnalystUser.get()).thenReturn(Optional.of(analyst("analyst-1")));
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.of(existing));

        EngineIntelligenceFeedbackResponse response = service.submit("txn-1", validRequest(), "feedback-key-1");

        assertThat(response.operationStatus()).isEqualTo("EXISTING");
        assertThat(response.feedbackId()).isEqualTo("feedback-existing");
        verify(feedbackRepository, never()).save(any());
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sameKeyDifferentTransactionDoesNotCollide() {
        when(scoredTransactionRepository.existsById("txn-2")).thenReturn(true);
        when(currentAnalystUser.get()).thenReturn(Optional.of(analyst("analyst-1")));
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-2"), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EngineIntelligenceFeedbackResponse response = service.submit("txn-2", validRequest(), "feedback-key-1");

        assertThat(response.operationStatus()).isEqualTo("CREATED");
        verify(feedbackRepository).save(any(EngineIntelligenceFeedbackDocument.class));
    }

    @Test
    void missingTransactionReturnsNotFound() {
        when(scoredTransactionRepository.existsById("txn-missing")).thenReturn(false);

        assertThatThrownBy(() -> service.submit("txn-missing", validRequest(), "feedback-key-1"))
                .isInstanceOf(EngineIntelligenceScoredTransactionNotFoundException.class);
    }

    @Test
    void invalidTransactionIdValidationMatchesReadApi() {
        assertThatThrownBy(() -> service.submit("txn/with spaces", validRequest(), "feedback-key-1"))
                .isInstanceOf(EngineIntelligenceScoredTransactionNotFoundException.class);
    }

    @Test
    void invalidFeedbackValuesAreRejectedBeforePersistence() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(currentAnalystUser.get()).thenReturn(Optional.of(analyst("analyst-1")));

        EngineIntelligenceFeedbackRequest request = new EngineIntelligenceFeedbackRequest(
                "finalDecision",
                "NOT_HELPFUL",
                "SIGNALS_LOOK_CORRECT",
                true,
                List.of("token-secret-stacktrace", "A", "B", "C", "D", "E"),
                "case-1"
        );

        assertThatThrownBy(() -> service.submit("txn-1", request, "feedback-key-1"))
                .isInstanceOf(InvalidEngineIntelligenceFeedbackRequestException.class)
                .satisfies(exception -> assertThat(((InvalidEngineIntelligenceFeedbackRequestException) exception).details())
                        .contains(
                                "feedbackType: forbidden value",
                                "selectedReasonCodes: max 5",
                                "selectedReasonCodes[0]: forbidden value"
                        ));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void submittedByAndSubmittedAtFromPayloadAreRejectedAsUnknownFields() {
        when(scoredTransactionRepository.existsById("txn-1")).thenReturn(true);
        when(currentAnalystUser.get()).thenReturn(Optional.of(analyst("analyst-1")));
        EngineIntelligenceFeedbackRequest request = validRequest();
        request.rejectUnknownField("submittedBy", "payload-user");
        request.rejectUnknownField("submittedAt", "2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.submit("txn-1", request, "feedback-key-1"))
                .isInstanceOf(InvalidEngineIntelligenceFeedbackRequestException.class)
                .satisfies(exception -> assertThat(((InvalidEngineIntelligenceFeedbackRequestException) exception).details())
                        .contains("submittedAt: unknown field", "submittedBy: unknown field"));
        verify(feedbackRepository, never()).save(any());
    }

    private EngineIntelligenceFeedbackRequest validRequest() {
        return new EngineIntelligenceFeedbackRequest(
                "ENGINE_INTELLIGENCE_USEFULNESS",
                "HELPFUL",
                "SIGNALS_LOOK_CORRECT",
                true,
                List.of("HIGH_VELOCITY"),
                "case-1"
        );
    }

    private AnalystPrincipal analyst(String userId) {
        return new AnalystPrincipal(userId, Set.of(AnalystRole.ANALYST), Set.of("engine-intelligence:feedback:write"));
    }

    private EngineIntelligenceFeedbackDocument existingDocument() {
        return new EngineIntelligenceFeedbackDocument(
                "feedback-existing",
                "txn-1",
                "case-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness.HELPFUL,
                com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                List.of("HIGH_VELOCITY"),
                "analyst-1",
                NOW,
                "corr-existing",
                idempotencyKeyPolicy.hashKey("feedback-key-1"),
                NOW
        );
    }
}
