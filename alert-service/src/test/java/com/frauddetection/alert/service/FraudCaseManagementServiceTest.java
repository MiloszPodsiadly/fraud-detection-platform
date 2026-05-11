package com.frauddetection.alert.service;

import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseTransitionPolicy;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDecisionRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionContext;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class FraudCaseManagementServiceTest {

    @Test
    void shouldCreateRapidTransferCaseWithGroupedTransactionDetails() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseManagementService service = service(fraudCaseRepository, scoredTransactionRepository, analystActorResolver, metrics, mock(RegulatedMutationCoordinator.class));

        var previousTransaction = scoredTransaction("rapid-txn-1", "rapid-customer-1", new BigDecimal("10000.00"));
        var currentEvent = TransactionFixtures.scoredTransaction()
                .withTransactionId("rapid-txn-2")
                .withCustomerId("rapid-customer-1")
                .withAmount(new BigDecimal("10000.00"), "PLN")
                .withRiskLevel(RiskLevel.CRITICAL)
                .withFeatureSnapshot(Map.of(
                        "rapidTransferFraudCaseCandidate", true,
                        "rapidTransferTransactionIds", List.of("rapid-txn-1", "rapid-txn-2"),
                        "rapidTransferTotalPln", new BigDecimal("20000.00"),
                        "rapidTransferThresholdPln", new BigDecimal("20000.00"),
                        "rapidTransferWindow", "PT1M",
                        "currentTransactionAmountPln", new BigDecimal("10000.00")
                ))
                .build();

        when(fraudCaseRepository.findByCaseKey("rapid-customer-1:RAPID_TRANSFER_BURST_20K_PLN:rapid-txn-1"))
                .thenReturn(Optional.empty());
        when(scoredTransactionRepository.findAllById(List.of("rapid-txn-1", "rapid-txn-2")))
                .thenReturn(List.of(previousTransaction));

        service.handleScoredTransaction(currentEvent);

        ArgumentCaptor<FraudCaseDocument> captor = ArgumentCaptor.forClass(FraudCaseDocument.class);
        verify(fraudCaseRepository).save(captor.capture());

        FraudCaseDocument savedCase = captor.getValue();
        assertThat(savedCase.getStatus()).isEqualTo(FraudCaseStatus.OPEN);
        assertThat(savedCase.getSuspicionType()).isEqualTo("RAPID_TRANSFER_BURST_20K_PLN");
        assertThat(savedCase.getTransactionIds()).containsExactly("rapid-txn-1", "rapid-txn-2");
        assertThat(savedCase.getTotalAmountPln()).isEqualByComparingTo("20000.00");
        assertThat(savedCase.getFirstTransactionAt()).isEqualTo(Instant.parse("2026-04-20T10:10:28Z"));
        assertThat(savedCase.getLastTransactionAt()).isEqualTo(Instant.parse("2026-04-20T10:15:28Z"));
        assertThat(savedCase.getTransactions())
                .extracting("transactionId")
                .containsExactly("rapid-txn-1", "rapid-txn-2");
        assertThat(savedCase.getTransactions())
                .extracting("amountPln")
                .containsExactly(new BigDecimal("10000.00"), new BigDecimal("10000.00"));
    }

    @Test
    void shouldNotBackfillMissingGroupedTransactionsWhenCaseIsRead() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseManagementService service = service(fraudCaseRepository, scoredTransactionRepository, analystActorResolver, metrics, mock(RegulatedMutationCoordinator.class));

        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setCaseKey("rapid-customer-1:RAPID_TRANSFER_BURST_20K_PLN:rapid-txn-1");
        storedCase.setCustomerId("rapid-customer-1");
        storedCase.setSuspicionType("RAPID_TRANSFER_BURST_20K_PLN");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactionIds(List.of("rapid-txn-1", "rapid-txn-2", "rapid-txn-3"));
        storedCase.setTransactions(List.of(scoredCaseTransaction("rapid-txn-3", new BigDecimal("6800.00"))));
        storedCase.setFirstTransactionAt(Instant.parse("2026-04-20T10:15:28Z"));
        storedCase.setLastTransactionAt(Instant.parse("2026-04-20T10:15:28Z"));

        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));

        FraudCaseDocument read = service.getCase("case-1");

        assertThat(read.getTransactions())
                .extracting("transactionId")
                .containsExactly("rapid-txn-3");
        assertThat(read.getFirstTransactionAt()).isEqualTo(Instant.parse("2026-04-20T10:15:28Z"));
        assertThat(read.getLastTransactionAt()).isEqualTo(Instant.parse("2026-04-20T10:15:28Z"));
        verify(scoredTransactionRepository, never()).findAllById(any());
        verify(fraudCaseRepository, never()).save(any(FraudCaseDocument.class));
    }

    @Test
    void shouldNotMutateCasesWhenListingOrSearching() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        FraudCaseSearchRepository searchRepository = mock(FraudCaseSearchRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseManagementService service = service(
                fraudCaseRepository,
                scoredTransactionRepository,
                analystActorResolver,
                metrics,
                mock(RegulatedMutationCoordinator.class),
                mock(FraudCaseAuditRepository.class),
                searchRepository
        );
        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactionIds(List.of("rapid-txn-1"));
        storedCase.setTransactions(List.of());
        storedCase.setCreatedAt(Instant.parse("2026-04-20T10:15:28Z"));
        storedCase.setUpdatedAt(Instant.parse("2026-04-20T10:15:28Z"));

        when(fraudCaseRepository.findAll()).thenReturn(List.of(storedCase));
        when(fraudCaseRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(storedCase)));
        when(searchRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(storedCase)));

        service.listCases();
        service.listCases(PageRequest.of(0, 10));
        service.searchCases(FraudCaseStatus.OPEN, null, null, null, null, null, null, PageRequest.of(0, 10));

        verify(scoredTransactionRepository, never()).findAllById(any());
        verify(fraudCaseRepository, never()).save(any(FraudCaseDocument.class));
    }

    @Test
    void shouldKeepSystemIngestionSeparateFromAnalystLifecycleAudit() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        FraudCaseAuditRepository auditRepository = mock(FraudCaseAuditRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseManagementService service = service(
                fraudCaseRepository,
                scoredTransactionRepository,
                analystActorResolver,
                metrics,
                mock(RegulatedMutationCoordinator.class),
                auditRepository,
                mock(FraudCaseSearchRepository.class)
        );
        var currentEvent = TransactionFixtures.scoredTransaction()
                .withTransactionId("rapid-txn-1")
                .withCustomerId("rapid-customer-1")
                .withFeatureSnapshot(Map.of(
                        "rapidTransferFraudCaseCandidate", true,
                        "rapidTransferTransactionIds", List.of("rapid-txn-1"),
                        "rapidTransferTotalPln", new BigDecimal("20000.00"),
                        "rapidTransferThresholdPln", new BigDecimal("20000.00"),
                        "rapidTransferWindow", "PT1M",
                        "currentTransactionAmountPln", new BigDecimal("20000.00")
                ))
                .build();

        when(fraudCaseRepository.findByCaseKey("rapid-customer-1:RAPID_TRANSFER_BURST_20K_PLN:rapid-txn-1"))
                .thenReturn(Optional.empty());
        when(scoredTransactionRepository.findAllById(List.of("rapid-txn-1"))).thenReturn(List.of());

        service.handleScoredTransaction(currentEvent);

        verify(fraudCaseRepository).save(any(FraudCaseDocument.class));
        verify(auditRepository, never()).save(any());
    }

    @Test
    void shouldDelegateLifecycleAndQueryCallsToInjectedServices() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        FraudCaseLifecycleService lifecycleService = mock(FraudCaseLifecycleService.class);
        FraudCaseQueryService queryService = mock(FraudCaseQueryService.class);
        FraudCaseManagementService service = new FraudCaseManagementService(
                fraudCaseRepository,
                scoredTransactionRepository,
                analystActorResolver,
                new FraudCaseUpdateMutationHandler(fraudCaseRepository, mock(AlertServiceMetrics.class)),
                mock(RegulatedMutationCoordinator.class),
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                lifecycleService,
                queryService
        );

        service.listCases();
        service.listCases(PageRequest.of(0, 10));
        service.getCase("case-1");
        service.searchCases(FraudCaseStatus.OPEN, null, null, null, null, null, null, PageRequest.of(0, 10));
        service.createCase(null, "create-key");
        service.assignCase("case-1", null, "assign-key");
        service.addNote("case-1", null, "note-key");
        service.addDecision("case-1", null, "decision-key");
        service.transitionCase("case-1", null, "transition-key");
        service.closeCase("case-1", null, "close-key");
        service.reopenCase("case-1", null, "reopen-key");
        service.auditTrail("case-1");

        verify(queryService).listCases();
        verify(queryService).listCases(any(org.springframework.data.domain.Pageable.class));
        verify(queryService).getCase("case-1");
        verify(queryService).searchCases(FraudCaseStatus.OPEN, null, null, null, null, null, null, PageRequest.of(0, 10));
        verify(queryService).auditTrail("case-1");
        verify(lifecycleService).createCase(null, "create-key");
        verify(lifecycleService).assignCase("case-1", null, "assign-key");
        verify(lifecycleService).addNote("case-1", null, "note-key");
        verify(lifecycleService).addDecision("case-1", null, "decision-key");
        verify(lifecycleService).transitionCase("case-1", null, "transition-key");
        verify(lifecycleService).closeCase("case-1", null, "close-key");
        verify(lifecycleService).reopenCase("case-1", null, "reopen-key");
    }

    @Test
    void shouldSubmitFraudCaseUpdateThroughRegulatedCoordinator() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        RegulatedMutationCoordinator coordinator = mock(RegulatedMutationCoordinator.class);
        FraudCaseManagementService service = service(fraudCaseRepository, scoredTransactionRepository, analystActorResolver, metrics, coordinator);

        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setCaseKey("rapid-customer-1:RAPID_TRANSFER_BURST_20K_PLN:rapid-txn-1");
        storedCase.setCustomerId("rapid-customer-1");
        storedCase.setSuspicionType("RAPID_TRANSFER_BURST_20K_PLN");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactionIds(List.of("rapid-txn-1"));
        storedCase.setTransactions(List.of(scoredCaseTransaction("rapid-txn-1", new BigDecimal("10000.00"))));

        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));
        when(fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(analystActorResolver.resolveActorId(eq("analyst-9"), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");
        when(coordinator.commit(any())).thenAnswer(invocation -> {
            RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = invocation.getArgument(0);
            FraudCaseDocument result = command.mutation().execute(new RegulatedMutationExecutionContext("command-1"));
            return new RegulatedMutationResult<>(RegulatedMutationState.EVIDENCE_PENDING,
                    command.responseMapper().response(result, RegulatedMutationState.EVIDENCE_PENDING));
        });

        UpdateFraudCaseResponse updated = service.updateCase("case-1", new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-9",
                "Confirmed after review",
                List.of("manual-review")
        ), "fraud-case-update-1");

        assertThat(updated.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
        assertThat(updated.updatedCase()).isNotNull();
        assertThat(updated.updatedCase().status()).isEqualTo(FraudCaseStatus.CONFIRMED_FRAUD);
        assertThat(updated.updatedCase().analystId()).isEqualTo("principal-9");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse>> commandCaptor =
                ArgumentCaptor.forClass(RegulatedMutationCommand.class);
        verify(coordinator).commit(commandCaptor.capture());
        RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = commandCaptor.getValue();
        assertThat(command.idempotencyKey()).isEqualTo("fraud-case-update-1");
        assertThat(command.action()).isEqualTo(AuditAction.UPDATE_FRAUD_CASE);
        assertThat(command.resourceType()).isEqualTo(AuditResourceType.FRAUD_CASE);
        assertThat(command.intent().intentHash()).isNotBlank();
        assertThat(command.requestHash()).isEqualTo(command.intent().payloadHash());
        verify(metrics).recordFraudCaseUpdated();
    }

    @Test
    void shouldNotReturnTargetBusinessFieldsForInProgressFraudCaseUpdate() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        RegulatedMutationCoordinator coordinator = mock(RegulatedMutationCoordinator.class);
        FraudCaseManagementService service = service(fraudCaseRepository, scoredTransactionRepository, analystActorResolver, metrics, coordinator);
        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactionIds(List.of());
        storedCase.setTransactions(List.of());

        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));
        when(analystActorResolver.resolveActorId(eq("analyst-alias"), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");
        when(coordinator.commit(any())).thenAnswer(invocation -> {
            RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = invocation.getArgument(0);
            return new RegulatedMutationResult<>(
                    RegulatedMutationState.REQUESTED,
                    command.statusResponseFactory().response(RegulatedMutationState.REQUESTED)
            );
        });

        UpdateFraudCaseResponse response = service.updateCase("case-1", new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-alias",
                "Confirmed after review",
                List.of("manual-review")
        ), "fraud-case-update-1");

        assertThat(response.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.IN_PROGRESS);
        assertThat(response.updatedCase()).isNull();
        assertThat(response.currentCaseSnapshot()).isNotNull();
        assertThat(response.currentCaseSnapshot().status()).isEqualTo(FraudCaseStatus.OPEN);
    }

    @Test
    void shouldHashFraudCaseRequestUsingResolvedActor() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        RegulatedMutationCoordinator coordinator = mock(RegulatedMutationCoordinator.class);
        FraudCaseManagementService service = service(fraudCaseRepository, scoredTransactionRepository, analystActorResolver, metrics, coordinator);
        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactionIds(List.of());
        storedCase.setTransactions(List.of());

        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));
        when(analystActorResolver.resolveActorId(any(), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");
        when(coordinator.commit(any())).thenAnswer(invocation -> {
            RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = invocation.getArgument(0);
            return new RegulatedMutationResult<>(
                    RegulatedMutationState.REQUESTED,
                    command.statusResponseFactory().response(RegulatedMutationState.REQUESTED)
            );
        });

        service.updateCase("case-1", new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-alias-a",
                "Confirmed after review",
                List.of("manual-review")
        ), "fraud-case-update-1");
        service.updateCase("case-1", new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-alias-b",
                "Confirmed after review",
                List.of("manual-review")
        ), "fraud-case-update-2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse>> commandCaptor =
                ArgumentCaptor.forClass(RegulatedMutationCommand.class);
        verify(coordinator, org.mockito.Mockito.times(2)).commit(commandCaptor.capture());
        assertThat(commandCaptor.getAllValues().get(0).requestHash())
                .isEqualTo(commandCaptor.getAllValues().get(1).requestHash());
    }

    @Test
    void shouldBlockFraudCaseUpdateWhenDurableAuditFails() {
        FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        AnalystActorResolver analystActorResolver = mock(AnalystActorResolver.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        RegulatedMutationCoordinator coordinator = mock(RegulatedMutationCoordinator.class);
        FraudCaseManagementService service = service(fraudCaseRepository, scoredTransactionRepository, analystActorResolver, metrics, coordinator);

        FraudCaseDocument storedCase = new FraudCaseDocument();
        storedCase.setCaseId("case-1");
        storedCase.setStatus(FraudCaseStatus.OPEN);
        storedCase.setTransactions(List.of(scoredCaseTransaction("rapid-txn-1", new BigDecimal("10000.00"))));

        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(storedCase));
        when(analystActorResolver.resolveActorId(eq("analyst-9"), eq("UPDATE_FRAUD_CASE"), eq("case-1")))
                .thenReturn("principal-9");
        when(coordinator.commit(any())).thenThrow(new AuditPersistenceUnavailableException());

        assertThatThrownBy(() -> service.updateCase("case-1", new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-9",
                "Confirmed after review",
                List.of("manual-review")
        ), "fraud-case-update-1")).isInstanceOf(AuditPersistenceUnavailableException.class);

        verify(fraudCaseRepository, never()).save(any(FraudCaseDocument.class));
        verify(metrics, never()).recordFraudCaseUpdated();
        assertThat(storedCase.getStatus()).isEqualTo(FraudCaseStatus.OPEN);
    }

    private FraudCaseManagementService service(
            FraudCaseRepository fraudCaseRepository,
            ScoredTransactionRepository scoredTransactionRepository,
            AnalystActorResolver analystActorResolver,
            AlertServiceMetrics metrics,
            RegulatedMutationCoordinator coordinator
    ) {
        RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
        when(transactionRunner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get());
        FraudCaseAuditRepository auditRepository = mock(FraudCaseAuditRepository.class);
        FraudCaseResponseMapper responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        return new FraudCaseManagementService(
                fraudCaseRepository,
                scoredTransactionRepository,
                analystActorResolver,
                new FraudCaseUpdateMutationHandler(fraudCaseRepository, metrics),
                coordinator,
                responseMapper,
                new FraudCaseLifecycleService(
                        fraudCaseRepository,
                        mock(AlertRepository.class),
                        mock(FraudCaseNoteRepository.class),
                        mock(FraudCaseDecisionRepository.class),
                        analystActorResolver,
                        transactionRunner,
                        new FraudCaseTransitionPolicy(),
                        new FraudCaseAuditService(auditRepository),
                        mock(com.frauddetection.alert.fraudcase.FraudCaseLifecycleIdempotencyService.class),
                        responseMapper
                ),
                new FraudCaseQueryService(
                        fraudCaseRepository,
                        auditRepository,
                        mock(FraudCaseSearchRepository.class),
                        responseMapper
                )
        );
    }

    private FraudCaseManagementService service(
            FraudCaseRepository fraudCaseRepository,
            ScoredTransactionRepository scoredTransactionRepository,
            AnalystActorResolver analystActorResolver,
            AlertServiceMetrics metrics,
            RegulatedMutationCoordinator coordinator,
            FraudCaseAuditRepository auditRepository,
            FraudCaseSearchRepository searchRepository
    ) {
        RegulatedMutationTransactionRunner transactionRunner = mock(RegulatedMutationTransactionRunner.class);
        when(transactionRunner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(0).get());
        FraudCaseResponseMapper responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        return new FraudCaseManagementService(
                fraudCaseRepository,
                scoredTransactionRepository,
                analystActorResolver,
                new FraudCaseUpdateMutationHandler(fraudCaseRepository, metrics),
                coordinator,
                responseMapper,
                new FraudCaseLifecycleService(
                        fraudCaseRepository,
                        mock(AlertRepository.class),
                        mock(FraudCaseNoteRepository.class),
                        mock(FraudCaseDecisionRepository.class),
                        analystActorResolver,
                        transactionRunner,
                        new FraudCaseTransitionPolicy(),
                        new FraudCaseAuditService(auditRepository),
                        mock(com.frauddetection.alert.fraudcase.FraudCaseLifecycleIdempotencyService.class),
                        responseMapper
                ),
                new FraudCaseQueryService(
                        fraudCaseRepository,
                        auditRepository,
                        searchRepository,
                        responseMapper
                )
        );
    }

    private ScoredTransactionDocument scoredTransaction(String transactionId, String customerId, BigDecimal amount) {
        return scoredTransaction(transactionId, customerId, amount, Instant.parse("2026-04-20T10:10:28Z"));
    }

    private ScoredTransactionDocument scoredTransaction(String transactionId, String customerId, BigDecimal amount, Instant transactionTimestamp) {
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId(transactionId);
        document.setCustomerId(customerId);
        document.setCorrelationId("corr-" + transactionId);
        document.setTransactionTimestamp(transactionTimestamp);
        document.setTransactionAmount(new Money(amount, "PLN"));
        document.setFraudScore(0.42d);
        document.setRiskLevel(RiskLevel.LOW);
        return document;
    }

    private com.frauddetection.alert.persistence.FraudCaseTransactionDocument scoredCaseTransaction(String transactionId, BigDecimal amountPln) {
        com.frauddetection.alert.persistence.FraudCaseTransactionDocument document = new com.frauddetection.alert.persistence.FraudCaseTransactionDocument();
        document.setTransactionId(transactionId);
        document.setCorrelationId("corr-" + transactionId);
        document.setTransactionTimestamp(Instant.parse("2026-04-20T10:15:28Z"));
        document.setAmountPln(amountPln);
        document.setFraudScore(0.94d);
        document.setRiskLevel(RiskLevel.CRITICAL);
        return document;
    }
}
