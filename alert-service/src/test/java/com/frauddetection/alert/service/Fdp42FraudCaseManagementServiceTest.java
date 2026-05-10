package com.frauddetection.alert.service;

import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AssignFraudCaseRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseDecisionType;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseConflictException;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseTransitionPolicy;
import com.frauddetection.alert.fraudcase.FraudCaseValidationException;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseAuditEntryDocument;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDecisionDocument;
import com.frauddetection.alert.persistence.FraudCaseDecisionRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp42FraudCaseManagementServiceTest {

    @Test
    void shouldCreateCaseAndAuditAtomicallyThroughTransactionRunner() {
        Fixture fixture = new Fixture();
        when(fixture.alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert("alert-1")));
        when(fixture.actorResolver.resolveActorId(eq("analyst-1"), eq("CREATE_FRAUD_CASE"), eq("new"))).thenReturn("analyst-1");
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FraudCaseDocument created = fixture.service.createCase(new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        ));

        assertThat(created.getCaseNumber()).startsWith("FC-");
        assertThat(created.getStatus()).isEqualTo(FraudCaseStatus.OPEN);
        assertThat(created.getLinkedAlertIds()).containsExactly("alert-1");
        assertThat(created.getTransactionIds()).isEmpty();
        verify(fixture.transactionRunner).runLocalCommit(any());
        ArgumentCaptor<FraudCaseAuditEntryDocument> auditCaptor = ArgumentCaptor.forClass(FraudCaseAuditEntryDocument.class);
        verify(fixture.auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo(FraudCaseAuditAction.CASE_CREATED);
        assertThat(auditCaptor.getValue().getNewStatus()).isEqualTo(FraudCaseStatus.OPEN);
    }

    @Test
    void shouldRejectCreateWithoutAlertsBeforePersistence() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.createCase(new CreateFraudCaseRequest(
                List.of(),
                FraudCasePriority.HIGH,
                RiskLevel.HIGH,
                null,
                "analyst-1"
        ))).isInstanceOf(FraudCaseValidationException.class);
    }

    @Test
    void shouldAuditAssignmentWithPreviousAndNewAssignee() {
        Fixture fixture = new Fixture();
        FraudCaseDocument document = openCase();
        document.setAssignedInvestigatorId("investigator-old");
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(document));
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("lead-1"), eq("ASSIGN_FRAUD_CASE"), eq("case-1"))).thenReturn("lead-1");

        FraudCaseDocument assigned = fixture.service.assignCase("case-1", new AssignFraudCaseRequest("investigator-new", "lead-1"));

        assertThat(assigned.getAssignedInvestigatorId()).isEqualTo("investigator-new");
        ArgumentCaptor<FraudCaseAuditEntryDocument> auditCaptor = ArgumentCaptor.forClass(FraudCaseAuditEntryDocument.class);
        verify(fixture.auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo(FraudCaseAuditAction.CASE_REASSIGNED);
        assertThat(auditCaptor.getValue().getDetails()).containsEntry("previousAssignee", "investigator-old");
        assertThat(auditCaptor.getValue().getDetails()).containsEntry("newAssignee", "investigator-new");
    }

    @Test
    void shouldAuditSameInvestigatorAssignmentAsReassignment() {
        Fixture fixture = new Fixture();
        FraudCaseDocument document = openCase();
        document.setAssignedInvestigatorId("investigator-1");
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(document));
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("lead-1"), eq("ASSIGN_FRAUD_CASE"), eq("case-1"))).thenReturn("lead-1");

        FraudCaseDocument assigned = fixture.service.assignCase("case-1", new AssignFraudCaseRequest("investigator-1", "lead-1"));

        assertThat(assigned.getAssignedInvestigatorId()).isEqualTo("investigator-1");
        ArgumentCaptor<FraudCaseAuditEntryDocument> auditCaptor = ArgumentCaptor.forClass(FraudCaseAuditEntryDocument.class);
        verify(fixture.auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getAction()).isEqualTo(FraudCaseAuditAction.CASE_REASSIGNED);
        assertThat(auditCaptor.getValue().getDetails()).containsEntry("previousAssignee", "investigator-1");
        assertThat(auditCaptor.getValue().getDetails()).containsEntry("newAssignee", "investigator-1");
    }

    @Test
    void shouldCreateIndependentCasesForDuplicateCreateRequests() {
        Fixture fixture = new Fixture();
        when(fixture.alertRepository.findAllById(List.of("alert-1"))).thenReturn(List.of(alert("alert-1")));
        when(fixture.actorResolver.resolveActorId(eq("analyst-1"), eq("CREATE_FRAUD_CASE"), eq("new"))).thenReturn("analyst-1");
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreateFraudCaseRequest request = new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        );

        FraudCaseDocument first = fixture.service.createCase(request);
        FraudCaseDocument second = fixture.service.createCase(request);

        assertThat(first.getCaseId()).isNotEqualTo(second.getCaseId());
        assertThat(first.getCaseNumber()).isNotEqualTo(second.getCaseNumber());
        verify(fixture.auditRepository, times(2)).save(any(FraudCaseAuditEntryDocument.class));
    }

    @Test
    void shouldRejectNoteOnClosedCase() {
        Fixture fixture = new Fixture();
        FraudCaseDocument document = openCase();
        document.setStatus(FraudCaseStatus.CLOSED);
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> fixture.service.addNote("case-1", new AddFraudCaseNoteRequest("note", false, "analyst-1")))
                .isInstanceOf(FraudCaseConflictException.class);
    }

    @Test
    void shouldAllowResolvedCasesToRemainMutableUntilClosed() {
        Fixture fixture = new Fixture();
        FraudCaseDocument document = openCase();
        document.setStatus(FraudCaseStatus.RESOLVED);
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(document));
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.noteRepository.save(any(FraudCaseNoteDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.decisionRepository.save(any(FraudCaseDecisionDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("analyst-1"), eq("ADD_FRAUD_CASE_NOTE"), eq("case-1"))).thenReturn("analyst-1");
        when(fixture.actorResolver.resolveActorId(eq("analyst-1"), eq("ADD_FRAUD_CASE_DECISION"), eq("case-1"))).thenReturn("analyst-1");
        when(fixture.actorResolver.resolveActorId(eq("lead-1"), eq("CLOSE_FRAUD_CASE"), eq("case-1"))).thenReturn("lead-1");

        fixture.service.addNote("case-1", new AddFraudCaseNoteRequest("resolved note", false, "analyst-1"));
        fixture.service.addDecision("case-1", new AddFraudCaseDecisionRequest(
                FraudCaseDecisionType.NO_ACTION,
                "resolved decision",
                "analyst-1"
        ));
        FraudCaseDocument closed = fixture.service.closeCase("case-1", new CloseFraudCaseRequest("Done", "lead-1"));

        assertThat(closed.getStatus()).isEqualTo(FraudCaseStatus.CLOSED);
        verify(fixture.noteRepository).save(any(FraudCaseNoteDocument.class));
        verify(fixture.decisionRepository).save(any(FraudCaseDecisionDocument.class));
        verify(fixture.auditRepository, times(3)).save(any(FraudCaseAuditEntryDocument.class));
    }

    @Test
    void shouldTreatRepeatedNotesAsSeparateLocalAuditedMutations() {
        Fixture fixture = new Fixture();
        FraudCaseDocument document = openCase();
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(document));
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.noteRepository.save(any(FraudCaseNoteDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("analyst-1"), eq("ADD_FRAUD_CASE_NOTE"), eq("case-1"))).thenReturn("analyst-1");

        fixture.service.addNote("case-1", new AddFraudCaseNoteRequest("same note", false, "analyst-1"));
        fixture.service.addNote("case-1", new AddFraudCaseNoteRequest("same note", false, "analyst-1"));

        ArgumentCaptor<FraudCaseNoteDocument> noteCaptor = ArgumentCaptor.forClass(FraudCaseNoteDocument.class);
        verify(fixture.noteRepository, times(2)).save(noteCaptor.capture());
        assertThat(noteCaptor.getAllValues()).extracting(FraudCaseNoteDocument::getBody)
                .containsExactly("same note", "same note");
        assertThat(noteCaptor.getAllValues()).extracting(FraudCaseNoteDocument::getId).doesNotHaveDuplicates();
        ArgumentCaptor<FraudCaseAuditEntryDocument> auditCaptor = ArgumentCaptor.forClass(FraudCaseAuditEntryDocument.class);
        verify(fixture.auditRepository, times(2)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).extracting(FraudCaseAuditEntryDocument::getAction)
                .containsExactly(FraudCaseAuditAction.NOTE_ADDED, FraudCaseAuditAction.NOTE_ADDED);
    }

    @Test
    void shouldTreatRepeatedDecisionsAsSeparateLocalAuditedMutations() {
        Fixture fixture = new Fixture();
        FraudCaseDocument document = openCase();
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(document));
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.decisionRepository.save(any(FraudCaseDecisionDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("analyst-1"), eq("ADD_FRAUD_CASE_DECISION"), eq("case-1"))).thenReturn("analyst-1");

        AddFraudCaseDecisionRequest request = new AddFraudCaseDecisionRequest(
                FraudCaseDecisionType.NEEDS_MORE_INFO,
                "same decision",
                "analyst-1"
        );
        fixture.service.addDecision("case-1", request);
        fixture.service.addDecision("case-1", request);

        ArgumentCaptor<FraudCaseDecisionDocument> decisionCaptor = ArgumentCaptor.forClass(FraudCaseDecisionDocument.class);
        verify(fixture.decisionRepository, times(2)).save(decisionCaptor.capture());
        assertThat(decisionCaptor.getAllValues()).extracting(FraudCaseDecisionDocument::getSummary)
                .containsExactly("same decision", "same decision");
        assertThat(decisionCaptor.getAllValues()).extracting(FraudCaseDecisionDocument::getId).doesNotHaveDuplicates();
        ArgumentCaptor<FraudCaseAuditEntryDocument> auditCaptor = ArgumentCaptor.forClass(FraudCaseAuditEntryDocument.class);
        verify(fixture.auditRepository, times(2)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).extracting(FraudCaseAuditEntryDocument::getAction)
                .containsExactly(FraudCaseAuditAction.DECISION_ADDED, FraudCaseAuditAction.DECISION_ADDED);
    }

    @Test
    void shouldRejectRepeatedCloseAsLifecycleConflict() {
        Fixture fixture = new Fixture();
        FraudCaseDocument document = openCase();
        document.setStatus(FraudCaseStatus.RESOLVED);
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(document));
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("lead-1"), eq("CLOSE_FRAUD_CASE"), eq("case-1"))).thenReturn("lead-1");

        fixture.service.closeCase("case-1", new CloseFraudCaseRequest("Resolved", "lead-1"));

        assertThatThrownBy(() -> fixture.service.closeCase("case-1", new CloseFraudCaseRequest("Resolved", "lead-1")))
                .isInstanceOf(FraudCaseConflictException.class);
        verify(fixture.auditRepository, times(1)).save(any(FraudCaseAuditEntryDocument.class));
    }

    @Test
    void shouldRejectRepeatedReopenAsLifecycleConflict() {
        Fixture fixture = new Fixture();
        FraudCaseDocument document = openCase();
        document.setStatus(FraudCaseStatus.CLOSED);
        when(fixture.fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(document));
        when(fixture.fraudCaseRepository.save(any(FraudCaseDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.auditRepository.save(any(FraudCaseAuditEntryDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("lead-1"), eq("REOPEN_FRAUD_CASE"), eq("case-1"))).thenReturn("lead-1");

        fixture.service.reopenCase("case-1", new ReopenFraudCaseRequest("New evidence", "lead-1"));

        assertThatThrownBy(() -> fixture.service.reopenCase("case-1", new ReopenFraudCaseRequest("New evidence", "lead-1")))
                .isInstanceOf(FraudCaseConflictException.class);
        verify(fixture.auditRepository, times(1)).save(any(FraudCaseAuditEntryDocument.class));
    }

    private static AlertDocument alert(String alertId) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        return document;
    }

    private static FraudCaseDocument openCase() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setCaseNumber("FC-20260510-ABCDEF12");
        document.setStatus(FraudCaseStatus.OPEN);
        document.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        return document;
    }

    private static final class Fixture {
        private final FraudCaseRepository fraudCaseRepository = mock(FraudCaseRepository.class);
        private final ScoredTransactionRepository scoredTransactionRepository = mock(ScoredTransactionRepository.class);
        private final AlertRepository alertRepository = mock(AlertRepository.class);
        private final FraudCaseNoteRepository noteRepository = mock(FraudCaseNoteRepository.class);
        private final FraudCaseDecisionRepository decisionRepository = mock(FraudCaseDecisionRepository.class);
        private final FraudCaseAuditRepository auditRepository = mock(FraudCaseAuditRepository.class);
        private final FraudCaseSearchRepository searchRepository = mock(FraudCaseSearchRepository.class);
        private final AnalystActorResolver actorResolver = mock(AnalystActorResolver.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final RegulatedMutationCoordinator coordinator = mock(RegulatedMutationCoordinator.class);
        private final RegulatedMutationTransactionRunner transactionRunner = transactionRunner();
        private final FraudCaseManagementService service = new FraudCaseManagementService(
                fraudCaseRepository,
                scoredTransactionRepository,
                alertRepository,
                noteRepository,
                decisionRepository,
                auditRepository,
                searchRepository,
                actorResolver,
                metrics,
                new FraudCaseUpdateMutationHandler(fraudCaseRepository, metrics),
                coordinator,
                transactionRunner,
                new FraudCaseTransitionPolicy(),
                new FraudCaseAuditService(auditRepository),
                new FraudCaseResponseMapper(new AlertResponseMapper())
        );

        private RegulatedMutationTransactionRunner transactionRunner() {
            RegulatedMutationTransactionRunner runner = mock(RegulatedMutationTransactionRunner.class);
            when(runner.runLocalCommit(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
            return runner;
        }
    }
}
