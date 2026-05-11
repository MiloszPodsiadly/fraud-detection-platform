package com.frauddetection.alert.regulated.invariant;

import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseTransitionPolicy;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDecisionRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.alert.service.FraudCaseLifecycleService;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.FraudCaseQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudCaseMutationInvariantTest {

    @Test
    void shouldNotExposeTargetBusinessStateForNonTerminalFraudCaseUpdate() {
        Fixture fixture = new Fixture();
        FraudCaseDocument storedCase = fixture.openCase();
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));
        when(fixture.actorResolver.resolveActorId(eq("analyst-alias"), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");
        when(fixture.coordinator.commit(any())).thenAnswer(invocation -> {
            RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = invocation.getArgument(0);
            return new RegulatedMutationResult<>(
                    RegulatedMutationState.BUSINESS_COMMITTING,
                    command.statusResponseFactory().response(RegulatedMutationState.BUSINESS_COMMITTING)
            );
        });

        UpdateFraudCaseResponse response = fixture.service.updateCase("case-1", request(), "fraud-case-update-1");

        assertThat(response.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMIT_UNKNOWN);
        assertThat(response.updatedCase()).isNull();
        assertThat(response.currentCaseSnapshot()).isNotNull();
        assertThat(response.currentCaseSnapshot().status()).isEqualTo(FraudCaseStatus.OPEN);
        verify(fixture.fraudCaseRepository, never()).save(any(FraudCaseDocument.class));
    }

    @Test
    void shouldBindFraudCaseUpdateIntentToResolvedActorAndIdempotencyKey() {
        Fixture fixture = new Fixture();
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(fixture.openCase()));
        when(fixture.actorResolver.resolveActorId(eq("analyst-alias"), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");
        when(fixture.coordinator.commit(any())).thenAnswer(invocation -> {
            RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = invocation.getArgument(0);
            return new RegulatedMutationResult<>(
                    RegulatedMutationState.REQUESTED,
                    command.statusResponseFactory().response(RegulatedMutationState.REQUESTED)
            );
        });

        fixture.service.updateCase("case-1", request(), "fraud-case-update-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse>> captor =
                ArgumentCaptor.forClass(RegulatedMutationCommand.class);
        verify(fixture.coordinator).commit(captor.capture());
        RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = captor.getValue();
        assertThat(command.idempotencyKey()).isEqualTo("fraud-case-update-1");
        assertThat(command.actorId()).isEqualTo("principal-9");
        assertThat(command.action()).isEqualTo(AuditAction.UPDATE_FRAUD_CASE);
        assertThat(command.intent().actorId()).isEqualTo("principal-9");
        assertThat(command.requestHash()).isEqualTo(command.intent().payloadHash());
    }

    private UpdateFraudCaseRequest request() {
        return new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-alias",
                "Confirmed after review",
                List.of("manual-review")
        );
    }

    private static final class Fixture {
        private final FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        private final ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        private final FraudCaseAuditRepository auditRepository = mock(FraudCaseAuditRepository.class);
        private final AnalystActorResolver actorResolver = mock(AnalystActorResolver.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final RegulatedMutationCoordinator coordinator = mock(RegulatedMutationCoordinator.class);
        private final RegulatedMutationTransactionRunner transactionRunner = transactionRunner();
        private final FraudCaseResponseMapper responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        private final FraudCaseManagementService service = new FraudCaseManagementService(
                fraudCaseRepository,
                scoredTransactionRepository,
                actorResolver,
                new FraudCaseUpdateMutationHandler(fraudCaseRepository, metrics),
                coordinator,
                responseMapper,
                new FraudCaseLifecycleService(
                        fraudCaseRepository,
                        mock(AlertRepository.class),
                        mock(FraudCaseNoteRepository.class),
                        mock(FraudCaseDecisionRepository.class),
                        actorResolver,
                        transactionRunner,
                        new FraudCaseTransitionPolicy(),
                        new FraudCaseAuditService(auditRepository),
                        mock(com.frauddetection.alert.fraudcase.FraudCaseLifecycleIdempotencyService.class)
                ),
                new FraudCaseQueryService(
                        fraudCaseRepository,
                        auditRepository,
                        mock(FraudCaseSearchRepository.class),
                        responseMapper
                )
        );

        private FraudCaseDocument openCase() {
            FraudCaseDocument document = new FraudCaseDocument();
            document.setCaseId("case-1");
            document.setStatus(FraudCaseStatus.OPEN);
            document.setTransactionIds(List.of());
            document.setTransactions(List.of());
            return document;
        }

        private RegulatedMutationTransactionRunner transactionRunner() {
            RegulatedMutationTransactionRunner runner = mock(RegulatedMutationTransactionRunner.class);
            when(runner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get());
            return runner;
        }
    }
}
