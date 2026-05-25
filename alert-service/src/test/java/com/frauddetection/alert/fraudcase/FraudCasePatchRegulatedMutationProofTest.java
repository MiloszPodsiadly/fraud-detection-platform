package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionContext;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.FraudCaseQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FraudCasePatchRegulatedMutationProofTest {

    private FraudCaseRepository fraudCaseRepository;
    private AnalystActorResolver actorResolver;
    private ReplaySafeCoordinator coordinator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        fraudCaseRepository = mock(FraudCaseRepository.class);
        actorResolver = mock(AnalystActorResolver.class);
        coordinator = new ReplaySafeCoordinator();
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseResponseMapper responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        FraudCaseManagementService service = new FraudCaseManagementService(
                fraudCaseRepository,
                mock(ScoredTransactionRepository.class),
                actorResolver,
                new FraudCaseUpdateMutationHandler(fraudCaseRepository, metrics),
                coordinator,
                responseMapper,
                new FraudCaseQueryService(
                        fraudCaseRepository,
                        mock(FraudCaseSearchRepository.class),
                        new FraudCaseWorkQueueProperties(Duration.ofHours(24), "test-work-queue-cursor-secret")
                )
        );
        FraudCaseController controller = new FraudCaseController(
                service,
                responseMapper,
                metrics,
                mock(SensitiveReadAuditService.class)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AlertServiceExceptionHandler())
                .build();

        when(fraudCaseRepository.findById("case-1")).thenAnswer(invocation -> Optional.of(openCase()));
        when(fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(actorResolver.resolveActorId(any(), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");
    }

    @Test
    void missingIdempotencyKeyReturnsControlledBadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("IN_REVIEW", "spoofed-actor", "review")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:MISSING_IDEMPOTENCY_KEY"))
                .andExpect(content().string(not(containsString("MissingRequestHeaderException"))));

        assertThat(coordinator.commitCount).isZero();
        verify(fraudCaseRepository, never()).save(any(FraudCaseDocument.class));
    }

    @Test
    void sameKeySamePayloadReplaysSafelyUsingBoundedIntent() throws Exception {
        String key = "raw-update-key-1";
        String request = payload("IN_REVIEW", "spoofed-actor", "sensitive-review-reason");

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotency_key_hash").isNotEmpty())
                .andExpect(content().string(not(containsString(key))));

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(key))));

        assertThat(coordinator.commitCount).isEqualTo(2);
        assertThat(coordinator.mutationCount).isEqualTo(1);
        assertThat(coordinator.lastCommand.actorId()).isEqualTo("principal-9");
        assertThat(coordinator.lastCommand.intent().actorId()).isEqualTo("principal-9");
        assertThat(coordinator.lastCommand.intent().status()).isEqualTo("IN_REVIEW");
        assertThat(coordinator.lastCommand.intent().notesHash()).doesNotContain("sensitive-review-reason");
        assertThat(coordinator.lastCommand.intent().payloadHash()).doesNotContain(key);
        verify(fraudCaseRepository, times(1)).save(any(FraudCaseDocument.class));
    }

    @Test
    void sameKeyDifferentPayloadReturnsConflictWithoutSecondMutation() throws Exception {
        String key = "raw-conflict-key";

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("IN_REVIEW", "spoofed-actor", "first-reason")))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("CONFIRMED_FRAUD", "spoofed-actor", "raw-second-payload")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("reason:IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD"))
                .andExpect(content().string(not(containsString("raw-second-payload"))))
                .andExpect(content().string(not(containsString("ConflictingIdempotencyKeyException"))));

        assertThat(coordinator.mutationCount).isEqualTo(1);
        verify(fraudCaseRepository, times(1)).save(any(FraudCaseDocument.class));
    }

    @Test
    void nonTerminalStateDoesNotExposeCommittedTargetState() throws Exception {
        coordinator.responseState = RegulatedMutationState.BUSINESS_COMMITTING;

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("X-Idempotency-Key", "update-key-uncertain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("CONFIRMED_FRAUD", "spoofed-actor", "review")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation_status").value("COMMIT_UNKNOWN"))
                .andExpect(jsonPath("$.updated_case").value(nullValue()))
                .andExpect(jsonPath("$.current_case_snapshot.status").value("OPEN"))
                .andExpect(jsonPath("$.recovery_required_reason").value("BUSINESS_COMMITTING"));

        assertThat(coordinator.mutationCount).isZero();
        verify(fraudCaseRepository, never()).save(any(FraudCaseDocument.class));
    }

    @Test
    void missingCaseDoesNotCommitMutation() throws Exception {
        when(fraudCaseRepository.findById("missing-case")).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/fraud-cases/missing-case")
                        .header("X-Idempotency-Key", "update-key-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("IN_REVIEW", "spoofed-actor", "review")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("reason:FRAUD_CASE_NOT_FOUND"))
                .andExpect(content().string(not(containsString("missing-case"))))
                .andExpect(content().string(not(containsString("ALERT_NOT_FOUND"))));

        assertThat(coordinator.commitCount).isZero();
        verify(fraudCaseRepository, never()).save(any(FraudCaseDocument.class));
    }

    private FraudCaseDocument openCase() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setStatus(FraudCaseStatus.OPEN);
        document.setTransactionIds(List.of());
        document.setTransactions(List.of());
        return document;
    }

    private String payload(String status, String analystId, String decisionReason) {
        return """
                {"status":"%s","analystId":"%s","decisionReason":"%s","tags":["manual-review"]}
                """.formatted(status, analystId, decisionReason);
    }

    private static final class ReplaySafeCoordinator implements RegulatedMutationCoordinator {
        private final Map<String, StoredResult> resultsByKey = new HashMap<>();
        private int commitCount;
        private int mutationCount;
        private RegulatedMutationState responseState;
        private RegulatedMutationCommand<?, ?> lastCommand;

        @Override
        public <R, S> RegulatedMutationResult<S> commit(RegulatedMutationCommand<R, S> command) {
            commitCount++;
            lastCommand = command;
            if (responseState != null) {
                return new RegulatedMutationResult<>(responseState, command.statusResponseFactory().response(responseState));
            }
            StoredResult existing = resultsByKey.get(command.idempotencyKey());
            if (existing != null) {
                if (!existing.requestHash.equals(command.requestHash())) {
                    throw new ConflictingIdempotencyKeyException();
                }
                @SuppressWarnings("unchecked")
                S response = (S) existing.response;
                return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING, response);
            }
            R saved = command.mutation().execute(new RegulatedMutationExecutionContext("command-1"));
            mutationCount++;
            S response = command.responseMapper().response(saved, RegulatedMutationState.EVIDENCE_PENDING);
            resultsByKey.put(command.idempotencyKey(), new StoredResult(command.requestHash(), response));
            return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING, response);
        }
    }

    private record StoredResult(String requestHash, Object response) {
    }
}
