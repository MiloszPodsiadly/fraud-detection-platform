package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackRepository;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.alert.engineintelligence.feedback.InvalidEngineIntelligenceFeedbackRequestException;
import com.frauddetection.alert.idempotency.IdempotencyCanonicalHasher;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.idempotency.SharedInvalidIdempotencyKeyException;
import com.frauddetection.alert.idempotency.SharedMissingIdempotencyKeyException;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private final RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
    private final EngineIntelligenceFeedbackService service = new EngineIntelligenceFeedbackService(
            scoredTransactionRepository,
            feedbackRepository,
            idempotencyKeyPolicy,
            currentAnalystUser,
            auditService,
            transactionRunner,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @BeforeEach
    void setUp() {
        when(transactionRunner.mode()).thenReturn(RegulatedMutationTransactionMode.OFF);
        when(transactionRunner.runLocalCommit(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    void createdFeedbackAlwaysAuditsSuccess() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EngineIntelligenceFeedbackResponse response = service.submit("txn-1", validRequest(), "feedback-key-1");

        assertThat(response.operationStatus()).isEqualTo("CREATED");
        assertThat(response.feedbackId()).isNotBlank();
        assertThat(response.transactionId()).isEqualTo("txn-1");
        assertThat(response.submittedAt()).isEqualTo(NOW);

        ArgumentCaptor<EngineIntelligenceFeedbackDocument> saved = ArgumentCaptor.forClass(EngineIntelligenceFeedbackDocument.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getFeedbackType()).isEqualTo(EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS);
        assertThat(saved.getValue().getIdempotencyKeyHash()).isNotEqualTo("feedback-key-1");
        assertThat(saved.getValue().getRequestPayloadHash()).isEqualTo(payloadHash(validRequest()));

        ArgumentCaptor<AuditEventMetadataSummary> metadata = ArgumentCaptor.forClass(AuditEventMetadataSummary.class);
        verify(auditService).audit(
                eq(AuditAction.SUBMIT_ENGINE_INTELLIGENCE_FEEDBACK),
                eq(AuditResourceType.ENGINE_INTELLIGENCE_FEEDBACK),
                eq(saved.getValue().getFeedbackId()),
                eq(saved.getValue().getCorrelationId()),
                eq("analyst-1"),
                any(),
                eq(null),
                metadata.capture(),
                eq(saved.getValue().getFeedbackId())
        );
        assertThat(metadata.getValue().filtersSummary())
                .contains("transaction_id=txn-1")
                .contains("feedback_type=ENGINE_INTELLIGENCE_USEFULNESS")
                .doesNotContain("fraud_case_id", "case-1", "payload", "token", "stacktrace", "endpoint");
    }

    @Test
    void auditFailureDoesNotReturnCreatedFeedback() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.submit("txn-1", validRequest(), "feedback-key-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    @Test
    void auditFailureDoesNotLeaveUnauditedCommittedFeedback() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.submit("txn-1", validRequest(), "feedback-key-1"))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<EngineIntelligenceFeedbackDocument> saved = ArgumentCaptor.forClass(EngineIntelligenceFeedbackDocument.class);
        verify(feedbackRepository).save(saved.capture());
        verify(feedbackRepository).deleteById(saved.getValue().getFeedbackId());
    }

    @Test
    void feedbackRepositorySaveFailureDoesNotAuditSuccess() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenThrow(new IllegalStateException("mongo unavailable"));

        assertThatThrownBy(() -> service.submit("txn-1", validRequest(), "feedback-key-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mongo unavailable");

        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(feedbackRepository, never()).deleteById(any());
    }

    @Test
    void sameIdempotencyKeySamePayloadReturnsExisting() {
        givenTransactionAndActor("txn-1", "analyst-1");
        EngineIntelligenceFeedbackDocument existing = existingDocument(validRequest());
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.of(existing));

        EngineIntelligenceFeedbackResponse response = service.submit("txn-1", validRequest(), "feedback-key-1");

        assertThat(response.operationStatus()).isEqualTo("EXISTING");
        assertThat(response.feedbackId()).isEqualTo("feedback-existing");
        verify(feedbackRepository, never()).save(any());
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sameIdempotencyKeyDifferentPayloadReturnsConflict() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.of(existingDocument(validRequest())));

        assertThatThrownBy(() -> service.submit("txn-1", requestWithUsefulness("NOT_HELPFUL"), "feedback-key-1"))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);

        verify(feedbackRepository, never()).save(any());
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateKeyRaceSamePayloadReturnsExisting() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty(), Optional.of(existingDocument(validRequest())));
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        EngineIntelligenceFeedbackResponse response = service.submit("txn-1", validRequest(), "feedback-key-1");

        assertThat(response.operationStatus()).isEqualTo("EXISTING");
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateKeyRaceDifferentPayloadReturnsConflict() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty(), Optional.of(existingDocument(validRequest())));
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.submit("txn-1", requestWithUsefulness("NOT_HELPFUL"), "feedback-key-1"))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);

        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingIdempotencyKeyDoesNotReachPersistence() {
        givenTransactionAndActor("txn-1", "analyst-1");

        assertThatThrownBy(() -> service.submit("txn-1", validRequest(), null))
                .isInstanceOf(SharedMissingIdempotencyKeyException.class);

        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void blankIdempotencyKeyDoesNotReachPersistence() {
        givenTransactionAndActor("txn-1", "analyst-1");

        assertThatThrownBy(() -> service.submit("txn-1", validRequest(), "   "))
                .isInstanceOf(SharedMissingIdempotencyKeyException.class);

        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void invalidIdempotencyKeyDoesNotReachPersistence() {
        givenTransactionAndActor("txn-1", "analyst-1");

        assertThatThrownBy(() -> service.submit("txn-1", validRequest(), "feedback-key-token/secret"))
                .isInstanceOf(SharedInvalidIdempotencyKeyException.class);

        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void fraudCaseIdCannotBeSpoofedOrIsNotAccepted() {
        givenTransactionAndActor("txn-1", "analyst-1");
        EngineIntelligenceFeedbackRequest request = validRequest();
        request.rejectUnknownField("fraudCaseId", "case-unrelated-to-this-transaction");

        assertThatThrownBy(() -> service.submit("txn-1", request, "feedback-key-1"))
                .isInstanceOf(InvalidEngineIntelligenceFeedbackRequestException.class)
                .satisfies(exception -> assertThat(((InvalidEngineIntelligenceFeedbackRequestException) exception).details())
                        .containsExactly("request: contains unknown fields"));

        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void feedbackDocumentDoesNotStoreClientSuppliedFraudCaseId() {
        assertThat(List.of(EngineIntelligenceFeedbackDocument.class.getDeclaredFields())
                .stream()
                .map(Field::getName))
                .doesNotContain("fraudCaseId");
    }

    @Test
    void auditDoesNotIncludeClientSuppliedFraudCaseId() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.submit("txn-1", validRequest(), "feedback-key-1");

        ArgumentCaptor<AuditEventMetadataSummary> metadata = ArgumentCaptor.forClass(AuditEventMetadataSummary.class);
        verify(auditService).audit(any(), any(), any(), any(), any(), any(), any(), metadata.capture(), any());
        assertThat(metadata.getValue().filtersSummary()).doesNotContain("fraud_case_id", "case-1");
    }

    @Test
    void unknownFieldNamedTokenSecretStacktraceReturnsBoundedErrorWithoutRawName() {
        assertUnknownFieldIsBounded("token-secret-stacktrace");
    }

    @Test
    void unknownFieldNamedRawEvidenceDoesNotLeakInError() {
        assertUnknownFieldIsBounded("rawEvidence");
    }

    @Test
    void unknownFieldNamedEndpointDoesNotLeakInError() {
        assertUnknownFieldIsBounded("endpoint");
    }

    @Test
    void submittedBySubmittedAtUnknownFieldsReturnBoundedError() {
        givenTransactionAndActor("txn-1", "analyst-1");
        EngineIntelligenceFeedbackRequest request = validRequest();
        request.rejectUnknownField("submittedBy", "payload-user");
        request.rejectUnknownField("submittedAt", "2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.submit("txn-1", request, "feedback-key-1"))
                .isInstanceOf(InvalidEngineIntelligenceFeedbackRequestException.class)
                .satisfies(exception -> assertThat(((InvalidEngineIntelligenceFeedbackRequestException) exception).details())
                        .containsExactly("request: contains unknown fields"));

        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void responseDoesNotContainTokenSecretStacktraceEndpointRawEvidence() {
        givenTransactionAndActor("txn-1", "analyst-1");
        EngineIntelligenceFeedbackRequest request = validRequest();
        request.rejectUnknownField("token-secret-stacktrace-endpoint-rawEvidence", "x");

        assertThatThrownBy(() -> service.submit("txn-1", request, "feedback-key-1"))
                .isInstanceOf(InvalidEngineIntelligenceFeedbackRequestException.class)
                .satisfies(exception -> assertThat(((InvalidEngineIntelligenceFeedbackRequestException) exception).details().toString())
                        .doesNotContain("token", "secret", "stacktrace", "endpoint", "rawEvidence"));
    }

    @Test
    void accuracyAssessmentIsRejectedAsReasonCode() {
        givenTransactionAndActor("txn-1", "analyst-1");

        assertThatThrownBy(() -> service.submit("txn-1", requestWithReasonCodes(List.of("SIGNALS_LOOK_CORRECT")), "feedback-key-1"))
                .isInstanceOf(InvalidEngineIntelligenceFeedbackRequestException.class)
                .satisfies(exception -> assertThat(((InvalidEngineIntelligenceFeedbackRequestException) exception).details())
                        .contains("selectedReasonCodes[0]: invalid reason code"));

        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void publicContractSelectedReasonCodeIsAccepted() {
        givenTransactionAndActor("txn-1", "analyst-1");
        when(feedbackRepository.findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(eq("analyst-1"), eq("txn-1"), any()))
                .thenReturn(Optional.empty());
        when(feedbackRepository.save(any(EngineIntelligenceFeedbackDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EngineIntelligenceFeedbackResponse response = service.submit(
                "txn-1",
                requestWithReasonCodes(List.of("HIGH_VELOCITY")),
                "feedback-key-1"
        );

        assertThat(response.operationStatus()).isEqualTo("CREATED");
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
        givenTransactionAndActor("txn-1", "analyst-1");

        EngineIntelligenceFeedbackRequest request = new EngineIntelligenceFeedbackRequest(
                "finalDecision",
                "NOT_HELPFUL",
                "SIGNALS_LOOK_CORRECT",
                true,
                List.of("token-secret-stacktrace", "A", "B", "C", "D", "E")
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

    private void assertUnknownFieldIsBounded(String fieldName) {
        givenTransactionAndActor("txn-1", "analyst-1");
        EngineIntelligenceFeedbackRequest request = validRequest();
        request.rejectUnknownField(fieldName, "x");

        assertThatThrownBy(() -> service.submit("txn-1", request, "feedback-key-1"))
                .isInstanceOf(InvalidEngineIntelligenceFeedbackRequestException.class)
                .satisfies(exception -> {
                    List<String> details = ((InvalidEngineIntelligenceFeedbackRequestException) exception).details();
                    assertThat(details).containsExactly("request: contains unknown fields");
                    assertThat(details.toString()).doesNotContain(fieldName);
                });

        verify(feedbackRepository, never()).save(any());
    }

    private void givenTransactionAndActor(String transactionId, String userId) {
        when(scoredTransactionRepository.existsById(transactionId)).thenReturn(true);
        when(currentAnalystUser.get()).thenReturn(Optional.of(analyst(userId)));
    }

    private EngineIntelligenceFeedbackRequest validRequest() {
        return requestWithReasonCodes(List.of("HIGH_VELOCITY"));
    }

    private EngineIntelligenceFeedbackRequest requestWithUsefulness(String usefulness) {
        return new EngineIntelligenceFeedbackRequest(
                "ENGINE_INTELLIGENCE_USEFULNESS",
                usefulness,
                "SIGNALS_LOOK_CORRECT",
                true,
                List.of("HIGH_VELOCITY")
        );
    }

    private EngineIntelligenceFeedbackRequest requestWithReasonCodes(List<String> reasonCodes) {
        return new EngineIntelligenceFeedbackRequest(
                "ENGINE_INTELLIGENCE_USEFULNESS",
                "HELPFUL",
                "SIGNALS_LOOK_CORRECT",
                true,
                reasonCodes
        );
    }

    private AnalystPrincipal analyst(String userId) {
        return new AnalystPrincipal(userId, Set.of(AnalystRole.ANALYST), Set.of("engine-intelligence:feedback:write"));
    }

    private EngineIntelligenceFeedbackDocument existingDocument(EngineIntelligenceFeedbackRequest request) {
        return new EngineIntelligenceFeedbackDocument(
                "feedback-existing",
                "txn-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                List.of("HIGH_VELOCITY"),
                "analyst-1",
                NOW,
                "corr-existing",
                idempotencyKeyPolicy.hashKey("feedback-key-1"),
                payloadHash(request),
                NOW
        );
    }

    private String payloadHash(EngineIntelligenceFeedbackRequest request) {
        return IdempotencyCanonicalHasher.hash(Map.of(
                "feedbackType", request.feedbackType(),
                "usefulness", request.usefulness(),
                "accuracyAssessment", request.accuracyAssessment(),
                "engineIntelligenceAvailable", request.engineIntelligenceAvailable(),
                "selectedReasonCodes", request.selectedReasonCodes()
        ));
    }
}
