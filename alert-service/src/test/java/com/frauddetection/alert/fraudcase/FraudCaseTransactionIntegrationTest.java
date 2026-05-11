package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.AssignFraudCaseRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseDecisionType;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
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
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.persistence.FraudCaseNoteDocument;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.FraudCaseLifecycleService;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.FraudCaseQueryService;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class FraudCaseTransactionIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private FraudCaseRepository caseRepository;
    private FraudCaseNoteRepository noteRepository;
    private FraudCaseDecisionRepository decisionRepository;
    private FraudCaseAuditRepository auditRepository;
    private FraudCaseLifecycleIdempotencyRepository idempotencyRepository;
    private AlertRepository alertRepository;
    private FraudCaseManagementService service;

    @BeforeEach
    void setUp() {
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl("fdp42_case_tx_" + UUID.randomUUID().toString().replace("-", ""))
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        caseRepository = repositoryFactory.getRepository(FraudCaseRepository.class);
        noteRepository = repositoryFactory.getRepository(FraudCaseNoteRepository.class);
        decisionRepository = repositoryFactory.getRepository(FraudCaseDecisionRepository.class);
        auditRepository = repositoryFactory.getRepository(FraudCaseAuditRepository.class);
        idempotencyRepository = repositoryFactory.getRepository(FraudCaseLifecycleIdempotencyRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        service = service(new FraudCaseAuditService(auditRepository));
        alertRepository.save(alert("alert-1"));
        alertRepository.save(alert("alert-2"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    @Test
    void shouldCommitCreateCaseAndAuditTogether() {
        FraudCaseDocument created = createCase();

        assertThat(caseRepository.findById(created.getCaseId())).isPresent();
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()))
                .extracting(FraudCaseAuditEntryDocument::getAction)
                .containsExactly(FraudCaseAuditAction.CASE_CREATED);
    }

    @Test
    void shouldCommitAssignmentAndAuditTogether() {
        FraudCaseDocument created = createCase();

        service.assignCase(created.getCaseId(), new AssignFraudCaseRequest("investigator-1", "lead-1"));

        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getAssignedInvestigatorId())
                .isEqualTo("investigator-1");
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()))
                .extracting(FraudCaseAuditEntryDocument::getAction)
                .containsExactly(FraudCaseAuditAction.CASE_CREATED, FraudCaseAuditAction.CASE_ASSIGNED);
    }

    @Test
    void shouldCommitNoteDecisionTransitionCloseAndReopenWithAudit() {
        FraudCaseDocument created = createCase();

        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("First note", true, "investigator-1"));
        service.addDecision(created.getCaseId(), new AddFraudCaseDecisionRequest(
                FraudCaseDecisionType.NEEDS_MORE_INFO,
                "Need second review",
                "investigator-1"
        ));
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.IN_REVIEW, "investigator-1"));
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.RESOLVED, "investigator-1"));
        service.closeCase(created.getCaseId(), new CloseFraudCaseRequest("Resolved after review", "investigator-1"));
        service.reopenCase(created.getCaseId(), new ReopenFraudCaseRequest("New evidence", "lead-1"));

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(decisionRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getStatus()).isEqualTo(FraudCaseStatus.REOPENED);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()))
                .extracting(FraudCaseAuditEntryDocument::getAction)
                .containsExactly(
                        FraudCaseAuditAction.CASE_CREATED,
                        FraudCaseAuditAction.NOTE_ADDED,
                        FraudCaseAuditAction.DECISION_ADDED,
                        FraudCaseAuditAction.STATUS_CHANGED,
                        FraudCaseAuditAction.STATUS_CHANGED,
                        FraudCaseAuditAction.CASE_CLOSED,
                        FraudCaseAuditAction.CASE_REOPENED
                );
    }

    @Test
    void shouldRollbackCreateWhenAuditAppendFails() {
        service = service(new FailingFraudCaseAuditService(auditRepository));

        assertThatThrownBy(this::createCase).isInstanceOf(IllegalStateException.class);

        assertThat(mongoTemplate.count(new Query(), FraudCaseDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), FraudCaseAuditEntryDocument.class)).isZero();
    }

    @Test
    void shouldRollbackNoteWhenAuditAppendFails() {
        FraudCaseDocument created = createCase();
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        service = service(new FailingFraudCaseAuditService(auditRepository));

        assertThatThrownBy(() -> service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("Duplicate proof", false, "analyst-1")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).isEmpty();
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void shouldRollbackDecisionWhenAuditAppendFails() {
        FraudCaseDocument created = createCase();
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        service = service(new FailingFraudCaseAuditService(auditRepository));

        assertThatThrownBy(() -> service.addDecision(created.getCaseId(), new AddFraudCaseDecisionRequest(
                FraudCaseDecisionType.NO_ACTION,
                "No action",
                "analyst-1"
        ))).isInstanceOf(IllegalStateException.class);

        assertThat(decisionRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).isEmpty();
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void shouldRollbackAssignmentWhenAuditAppendFails() {
        FraudCaseDocument created = createCase();
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        long auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        service = service(new FailingFraudCaseAuditService(auditRepository));

        assertThatThrownBy(() -> service.assignCase(created.getCaseId(), new AssignFraudCaseRequest("investigator-1", "lead-1")))
                .isInstanceOf(IllegalStateException.class);

        FraudCaseDocument persisted = caseRepository.findById(created.getCaseId()).orElseThrow();
        assertThat(persisted.getAssignedInvestigatorId()).isNull();
        assertThat(persisted.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize((int) auditBefore);
    }

    @Test
    void shouldRollbackTransitionWhenAuditAppendFails() {
        FraudCaseDocument created = createCase();
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        long auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        service = service(new FailingFraudCaseAuditService(auditRepository));

        assertThatThrownBy(() -> service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.IN_REVIEW, "analyst-1")))
                .isInstanceOf(IllegalStateException.class);

        FraudCaseDocument persisted = caseRepository.findById(created.getCaseId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(FraudCaseStatus.OPEN);
        assertThat(persisted.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize((int) auditBefore);
    }

    @Test
    void shouldRollbackCloseWhenAuditAppendFails() {
        FraudCaseDocument created = createCase();
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.IN_REVIEW, "analyst-1"));
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.RESOLVED, "analyst-1"));
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        long auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        service = service(new FailingFraudCaseAuditService(auditRepository));

        assertThatThrownBy(() -> service.closeCase(created.getCaseId(), new CloseFraudCaseRequest("Resolved", "lead-1")))
                .isInstanceOf(IllegalStateException.class);

        FraudCaseDocument persisted = caseRepository.findById(created.getCaseId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(FraudCaseStatus.RESOLVED);
        assertThat(persisted.getClosedAt()).isNull();
        assertThat(persisted.getClosureReason()).isNull();
        assertThat(persisted.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize((int) auditBefore);
    }

    @Test
    void shouldRollbackReopenWhenAuditAppendFails() {
        FraudCaseDocument created = createCase();
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.IN_REVIEW, "analyst-1"));
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.RESOLVED, "analyst-1"));
        service.closeCase(created.getCaseId(), new CloseFraudCaseRequest("Resolved", "lead-1"));
        FraudCaseDocument closed = caseRepository.findById(created.getCaseId()).orElseThrow();
        Instant originalUpdatedAt = closed.getUpdatedAt();
        Instant originalClosedAt = closed.getClosedAt();
        long auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        service = service(new FailingFraudCaseAuditService(auditRepository));

        assertThatThrownBy(() -> service.reopenCase(created.getCaseId(), new ReopenFraudCaseRequest("New evidence", "lead-1")))
                .isInstanceOf(IllegalStateException.class);

        FraudCaseDocument persisted = caseRepository.findById(created.getCaseId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(FraudCaseStatus.CLOSED);
        assertThat(persisted.getClosedAt()).isEqualTo(originalClosedAt);
        assertThat(persisted.getClosureReason()).isEqualTo("Resolved");
        assertThat(persisted.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize((int) auditBefore);
    }

    @Test
    void shouldKeepAuditEntriesAppendOnlyOrderedAndOnePerLifecycleMutation() {
        FraudCaseDocument created = createCase();
        List<FraudCaseAuditEntryDocument> afterCreate = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId());
        FraudCaseAuditEntryDocument firstEntry = afterCreate.get(0);
        Map<String, String> firstDetails = Map.copyOf(firstEntry.getDetails());

        service.assignCase(created.getCaseId(), new AssignFraudCaseRequest("investigator-1", "lead-1"));
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(afterCreate.size() + 1);
        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("note", false, "investigator-1"));
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(afterCreate.size() + 2);
        service.addDecision(created.getCaseId(), new AddFraudCaseDecisionRequest(
                FraudCaseDecisionType.NO_ACTION,
                "decision",
                "investigator-1"
        ));
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(afterCreate.size() + 3);

        List<FraudCaseAuditEntryDocument> audits = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId());
        assertThat(audits).extracting(FraudCaseAuditEntryDocument::getOccurredAt).isSorted();
        FraudCaseAuditEntryDocument persistedFirst = audits.get(0);
        assertThat(persistedFirst.getId()).isEqualTo(firstEntry.getId());
        assertThat(persistedFirst.getAction()).isEqualTo(firstEntry.getAction());
        assertThat(persistedFirst.getDetails()).isEqualTo(firstDetails);
    }

    @Test
    void shouldRollbackWhenCallbackThrowsAfterCaseSaveBeforeAudit() {
        RegulatedMutationTransactionRunner runner = transactionRunner();
        long auditBefore = mongoTemplate.count(new Query(), FraudCaseAuditEntryDocument.class);

        assertThatThrownBy(() -> runner.runLocalCommit(() -> {
            FraudCaseDocument document = new FraudCaseDocument();
            document.setCaseId("case-before-audit-fail");
            document.setCaseKey("case-before-audit-fail");
            document.setCaseNumber("FC-ROLLBACK-1");
            document.setStatus(FraudCaseStatus.OPEN);
            document.setPriority(FraudCasePriority.HIGH);
            document.setRiskLevel(RiskLevel.HIGH);
            document.setLinkedAlertIds(List.of("alert-1"));
            document.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
            document.setUpdatedAt(Instant.parse("2026-05-10T10:00:00Z"));
            caseRepository.save(document);
            throw new IllegalStateException("failed before audit append");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(caseRepository.findById("case-before-audit-fail")).isEmpty();
        assertThat(mongoTemplate.count(new Query(), FraudCaseAuditEntryDocument.class)).isEqualTo(auditBefore);
    }

    @Test
    void shouldNotWriteAuditWhenCaseSaveFailsBeforeAudit() {
        long auditBefore = mongoTemplate.count(new Query(), FraudCaseAuditEntryDocument.class);

        assertThatThrownBy(() -> transactionRunner().runLocalCommit(() -> {
            throw new IllegalStateException("case save failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(mongoTemplate.count(new Query(), FraudCaseAuditEntryDocument.class)).isEqualTo(auditBefore);
    }

    @Test
    void shouldReplaySameKeyAddNoteWithoutDuplicateMutationOrAudit() {
        FraudCaseDocument created = createCase();
        AddFraudCaseNoteRequest request = new AddFraudCaseNoteRequest("Retry-safe note", true, "analyst-1");

        var first = service.addNote(created.getCaseId(), request, "note-key-1");
        var replay = service.addNote(created.getCaseId(), request, "note-key-1");

        assertThat(replay).isEqualTo(first);
        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()))
                .extracting(FraudCaseAuditEntryDocument::getAction)
                .containsExactly(FraudCaseAuditAction.CASE_CREATED, FraudCaseAuditAction.NOTE_ADDED);
        assertThat(idempotencyRepository.findAll())
                .filteredOn(record -> record.getAction().equals("ADD_FRAUD_CASE_NOTE"))
                .hasSize(1)
                .first()
                .satisfies(record -> {
                    assertThat(record.getStatus()).isEqualTo(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
                    assertThat(record.getExpiresAt()).isEqualTo(record.getCreatedAt().plus(Duration.ofHours(24)));
                });
    }

    @Test
    void shouldRejectSameKeyDifferentPayloadWithoutMutationOrAudit() {
        FraudCaseDocument created = createCase();
        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("First note", true, "analyst-1"), "note-key-2");
        int notesBefore = noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId()).size();
        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Different note", true, "analyst-1"),
                "note-key-2"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(notesBefore);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
    }

    @Test
    void shouldRejectSameKeyDifferentActionWithoutMutationOrAudit() {
        FraudCaseDocument created = createCase();
        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("First note", true, "analyst-1"), "global-key-action");
        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();

        assertThatThrownBy(() -> service.closeCase(
                created.getCaseId(),
                new CloseFraudCaseRequest("Different lifecycle operation", "analyst-1"),
                "global-key-action"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);

        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getStatus()).isEqualTo(FraudCaseStatus.OPEN);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.CASE_CLOSED)).isZero();
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
        assertThat(idempotencyRepository.findAll()).hasSize(1);
    }

    @Test
    void shouldRejectSameKeyDifferentScopeWithoutMutationOrAudit() {
        FraudCaseDocument firstCase = createCase("alert-1");
        FraudCaseDocument secondCase = createCase("alert-2");
        service.addNote(firstCase.getCaseId(), new AddFraudCaseNoteRequest("First note", true, "analyst-1"), "global-key-scope");
        int secondCaseAuditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(secondCase.getCaseId()).size();

        assertThatThrownBy(() -> service.addNote(
                secondCase.getCaseId(),
                new AddFraudCaseNoteRequest("Second case note", true, "analyst-1"),
                "global-key-scope"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(secondCase.getCaseId())).isEmpty();
        assertThat(countAudit(secondCase.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isZero();
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(secondCase.getCaseId())).hasSize(secondCaseAuditBefore);
        assertThat(idempotencyRepository.findAll()).hasSize(1);
    }

    @Test
    void shouldRejectSameKeyDifferentActorWithoutMutationOrAudit() {
        FraudCaseDocument created = createCase();
        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("First note", true, "analyst-1"), "global-key-actor");
        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("First note", true, "analyst-2"),
                "global-key-actor"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isEqualTo(1);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
        assertThat(idempotencyRepository.findAll()).hasSize(1);
    }

    @Test
    void shouldReplayAndConflictAssignCaseByIdempotencyKey() {
        FraudCaseDocument created = createCase();
        AssignFraudCaseRequest request = new AssignFraudCaseRequest("investigator-1", "lead-1");

        FraudCaseDocument first = service.assignCase(created.getCaseId(), request, "assign-key-1");
        FraudCaseDocument replay = service.assignCase(created.getCaseId(), request, "assign-key-1");

        assertThat(replay.getAssignedInvestigatorId()).isEqualTo(first.getAssignedInvestigatorId());
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.CASE_ASSIGNED)).isEqualTo(1);
        assertOneCompletedRecord("ASSIGN_FRAUD_CASE", created.getCaseId());

        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        assertThatThrownBy(() -> service.assignCase(
                created.getCaseId(),
                new AssignFraudCaseRequest("investigator-2", "lead-1"),
                "assign-key-1"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getAssignedInvestigatorId())
                .isEqualTo("investigator-1");
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
    }

    @Test
    void shouldReplayAndConflictDecisionByIdempotencyKey() {
        FraudCaseDocument created = createCase();
        AddFraudCaseDecisionRequest request = new AddFraudCaseDecisionRequest(
                FraudCaseDecisionType.NEEDS_MORE_INFO,
                "Need second review",
                "investigator-1"
        );

        var first = service.addDecision(created.getCaseId(), request, "decision-key-1");
        var replay = service.addDecision(created.getCaseId(), request, "decision-key-1");

        assertThat(replay).isEqualTo(first);
        assertThat(decisionRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.DECISION_ADDED)).isEqualTo(1);
        assertOneCompletedRecord("ADD_FRAUD_CASE_DECISION", created.getCaseId());

        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        assertThatThrownBy(() -> service.addDecision(
                created.getCaseId(),
                new AddFraudCaseDecisionRequest(FraudCaseDecisionType.NO_ACTION, "No action", "investigator-1"),
                "decision-key-1"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);
        assertThat(decisionRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
    }

    @Test
    void shouldReplayAndConflictTransitionByIdempotencyKey() {
        FraudCaseDocument created = createCase();
        TransitionFraudCaseRequest request = new TransitionFraudCaseRequest(FraudCaseStatus.IN_REVIEW, "analyst-1");

        FraudCaseDocument first = service.transitionCase(created.getCaseId(), request, "transition-key-1");
        FraudCaseDocument replay = service.transitionCase(created.getCaseId(), request, "transition-key-1");

        assertThat(replay.getStatus()).isEqualTo(first.getStatus()).isEqualTo(FraudCaseStatus.IN_REVIEW);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.STATUS_CHANGED)).isEqualTo(1);
        assertOneCompletedRecord("TRANSITION_FRAUD_CASE", created.getCaseId());

        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        assertThatThrownBy(() -> service.transitionCase(
                created.getCaseId(),
                new TransitionFraudCaseRequest(FraudCaseStatus.ESCALATED, "analyst-1"),
                "transition-key-1"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getStatus())
                .isEqualTo(FraudCaseStatus.IN_REVIEW);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
    }

    @Test
    void shouldReplayAndConflictCloseByIdempotencyKey() {
        FraudCaseDocument created = createCase();
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.IN_REVIEW, "analyst-1"));
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.RESOLVED, "analyst-1"));
        CloseFraudCaseRequest request = new CloseFraudCaseRequest("Resolved", "lead-1");

        FraudCaseDocument first = service.closeCase(created.getCaseId(), request, "close-key-replay");
        FraudCaseDocument replay = service.closeCase(created.getCaseId(), request, "close-key-replay");

        assertThat(replay.getStatus()).isEqualTo(first.getStatus()).isEqualTo(FraudCaseStatus.CLOSED);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.CASE_CLOSED)).isEqualTo(1);
        assertOneCompletedRecord("CLOSE_FRAUD_CASE", created.getCaseId());

        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        assertThatThrownBy(() -> service.closeCase(
                created.getCaseId(),
                new CloseFraudCaseRequest("Different reason", "lead-1"),
                "close-key-replay"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
    }

    @Test
    void shouldReplayAndConflictReopenByIdempotencyKey() {
        FraudCaseDocument created = createCase();
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.IN_REVIEW, "analyst-1"));
        service.transitionCase(created.getCaseId(), new TransitionFraudCaseRequest(FraudCaseStatus.RESOLVED, "analyst-1"));
        service.closeCase(created.getCaseId(), new CloseFraudCaseRequest("Resolved", "lead-1"));
        ReopenFraudCaseRequest request = new ReopenFraudCaseRequest("New evidence", "lead-1");

        FraudCaseDocument first = service.reopenCase(created.getCaseId(), request, "reopen-key-replay");
        FraudCaseDocument replay = service.reopenCase(created.getCaseId(), request, "reopen-key-replay");

        assertThat(replay.getStatus()).isEqualTo(first.getStatus()).isEqualTo(FraudCaseStatus.REOPENED);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.CASE_REOPENED)).isEqualTo(1);
        assertOneCompletedRecord("REOPEN_FRAUD_CASE", created.getCaseId());

        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();
        assertThatThrownBy(() -> service.reopenCase(
                created.getCaseId(),
                new ReopenFraudCaseRequest("Different reason", "lead-1"),
                "reopen-key-replay"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
    }

    @Test
    void shouldReplaySameKeyCreateCaseWithoutSecondCaseOrAudit() {
        CreateFraudCaseRequest request = new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        );

        FraudCaseDocument first = service.createCase(request, "create-key-1");
        FraudCaseDocument replay = service.createCase(request, "create-key-1");

        assertThat(replay.getCaseId()).isEqualTo(first.getCaseId());
        assertThat(caseRepository.findAll()).hasSize(1);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(first.getCaseId())).hasSize(1);
    }

    @Test
    void shouldRejectMissingIdempotencyKeyOnIdempotentLifecyclePathWithoutMutation() {
        CreateFraudCaseRequest request = new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        );

        assertThatThrownBy(() -> service.createCase(request, null))
                .isInstanceOf(FraudCaseMissingIdempotencyKeyException.class);

        assertThat(caseRepository.findAll()).isEmpty();
        assertThat(auditRepository.findAll()).isEmpty();
        assertThat(idempotencyRepository.findAll()).isEmpty();
    }

    @Test
    void shouldFailClosedWhenRequiredIdempotencyServiceIsMissing() {
        service = service(new FraudCaseAuditService(auditRepository), IdempotencyServiceMode.NULL_IDEMPOTENCY);
        FraudCaseDocument created = createCase();
        int auditBefore = auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId()).size();

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Must not commit", false, "analyst-1"),
                "missing-service-key"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Fraud-case lifecycle idempotency is required but not configured.");

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).isEmpty();
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(created.getCaseId())).hasSize(auditBefore);
        assertThat(idempotencyRepository.findAll()).isEmpty();
    }

    @Test
    void shouldAllowInternalCompatibilityPathWhenIdempotencyServiceIsMissing() {
        service = service(new FraudCaseAuditService(auditRepository), IdempotencyServiceMode.NULL_IDEMPOTENCY);
        FraudCaseDocument created = createCase();

        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("Internal note", false, "analyst-1"));

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isEqualTo(1);
        assertThat(idempotencyRepository.findAll()).isEmpty();
    }

    @Test
    void shouldRollbackIdempotencyRecordWhenAuditAppendFails() {
        FraudCaseDocument created = createCase();
        service = service(new FailingFraudCaseAuditService(auditRepository));

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Rollback note", false, "analyst-1"),
                "note-key-rollback"
        )).isInstanceOf(IllegalStateException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).isEmpty();
        assertThat(idempotencyRepository.findAll())
                .noneMatch(record -> "ADD_FRAUD_CASE_NOTE".equals(record.getAction())
                        && created.getCaseId().equals(record.getCaseIdScope()));
    }

    @Test
    void shouldRollbackMutationAndAuditWhenCompletionSaveFails() {
        FraudCaseDocument created = createCase();
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        service = service(new FraudCaseAuditService(auditRepository), IdempotencyServiceMode.FAIL_COMPLETION_SAVE);

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Completion rollback note", false, "analyst-1"),
                "note-key-completion-fail"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency completion save failed");

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).isEmpty();
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isZero();
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(idempotencyRepository.findAll())
                .noneMatch(record -> "ADD_FRAUD_CASE_NOTE".equals(record.getAction())
                        && record.getStatus() == FraudCaseLifecycleIdempotencyStatus.COMPLETED);
    }

    @Test
    void shouldRollbackMutationAndAuditWhenResponseSnapshotIsTooLarge() {
        FraudCaseDocument created = createCase();
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        service = service(new FraudCaseAuditService(auditRepository), IdempotencyServiceMode.TINY_SNAPSHOT_LIMIT);

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Snapshot rollback note", false, "analyst-1"),
                "note-key-snapshot-too-large"
        )).isInstanceOf(FraudCaseIdempotencySnapshotTooLargeException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).isEmpty();
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isZero();
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(idempotencyRepository.findAll())
                .noneMatch(record -> "ADD_FRAUD_CASE_NOTE".equals(record.getAction()));
    }

    private FraudCaseDocument createCase() {
        return createCase("alert-1");
    }

    private FraudCaseDocument createCase(String alertId) {
        return service.createCase(new CreateFraudCaseRequest(
                List.of(alertId),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        ));
    }

    private FraudCaseManagementService service(FraudCaseAuditService auditService) {
        return service(auditService, IdempotencyServiceMode.NORMAL);
    }

    private FraudCaseManagementService service(FraudCaseAuditService auditService, IdempotencyServiceMode mode) {
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        var scoredTransactionRepository = new MongoRepositoryFactory(mongoTemplate).getRepository(ScoredTransactionRepository.class);
        var searchRepository = new MongoFraudCaseSearchRepository(mongoTemplate);
        var actorResolver = new AnalystActorResolver(new CurrentAnalystUser(), metrics);
        var transactionRunner = transactionRunner();
        var responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        var idempotencyService = mode == IdempotencyServiceMode.NULL_IDEMPOTENCY
                ? null
                : idempotencyService(
                        idempotencyRepository,
                        new SharedIdempotencyKeyPolicy(),
                        new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                        transactionRunner,
                        JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                        mode
                );
        return new FraudCaseManagementService(
                caseRepository,
                scoredTransactionRepository,
                actorResolver,
                new FraudCaseUpdateMutationHandler(caseRepository, metrics),
                unusedRegulatedMutationCoordinator(),
                responseMapper,
                new FraudCaseLifecycleService(
                        caseRepository,
                        alertRepository,
                        noteRepository,
                        decisionRepository,
                        actorResolver,
                        transactionRunner,
                        new FraudCaseTransitionPolicy(),
                        auditService,
                        idempotencyService
                ),
                new FraudCaseQueryService(
                        caseRepository,
                        auditRepository,
                        searchRepository,
                        responseMapper
                )
        );
    }

    private FraudCaseLifecycleIdempotencyService idempotencyService(
            FraudCaseLifecycleIdempotencyRepository repository,
            SharedIdempotencyKeyPolicy keyPolicy,
            FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
            RegulatedMutationTransactionRunner transactionRunner,
            JsonMapper objectMapper,
            IdempotencyServiceMode mode
    ) {
        if (mode == IdempotencyServiceMode.FAIL_COMPLETION_SAVE) {
            return new CompletionFailingFraudCaseLifecycleIdempotencyService(
                    repository,
                    keyPolicy,
                    conflictPolicy,
                    transactionRunner,
                    objectMapper
            );
        }
        int snapshotLimit = mode == IdempotencyServiceMode.TINY_SNAPSHOT_LIMIT
                ? 8
                : FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES;
        return new FraudCaseLifecycleIdempotencyService(
                repository,
                keyPolicy,
                conflictPolicy,
                transactionRunner,
                objectMapper,
                snapshotLimit
        );
    }

    private RegulatedMutationTransactionRunner transactionRunner() {
        return new RegulatedMutationTransactionRunner(
                "REQUIRED",
                new FixedTransactionManagerProvider(new MongoTransactionManager(databaseFactory))
        );
    }

    private RegulatedMutationCoordinator unusedRegulatedMutationCoordinator() {
        return new RegulatedMutationCoordinator() {
            @Override
            public <R, S> RegulatedMutationResult<S> commit(RegulatedMutationCommand<R, S> command) {
                throw new AssertionError("FDP-42 local lifecycle operations must not use RegulatedMutationCoordinator.");
            }
        };
    }

    private AlertDocument alert(String alertId) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        document.setTransactionId(alertId + "-transaction");
        document.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        return document;
    }

    private long countAudit(String caseId, FraudCaseAuditAction action) {
        return auditRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(entry -> entry.getAction() == action)
                .count();
    }

    private void assertOneCompletedRecord(String action, String caseIdScope) {
        assertThat(idempotencyRepository.findAll())
                .filteredOn(record -> action.equals(record.getAction()) && caseIdScope.equals(record.getCaseIdScope()))
                .hasSize(1)
                .first()
                .extracting(FraudCaseLifecycleIdempotencyRecordDocument::getStatus)
                .isEqualTo(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
    }

    private enum IdempotencyServiceMode {
        NORMAL,
        FAIL_COMPLETION_SAVE,
        TINY_SNAPSHOT_LIMIT,
        NULL_IDEMPOTENCY
    }

    private static final class CompletionFailingFraudCaseLifecycleIdempotencyService extends FraudCaseLifecycleIdempotencyService {
        private CompletionFailingFraudCaseLifecycleIdempotencyService(
                FraudCaseLifecycleIdempotencyRepository repository,
                SharedIdempotencyKeyPolicy keyPolicy,
                FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
                RegulatedMutationTransactionRunner transactionRunner,
                JsonMapper objectMapper
        ) {
            super(
                    repository,
                    keyPolicy,
                    conflictPolicy,
                    transactionRunner,
                    objectMapper,
                    MAX_RESPONSE_SNAPSHOT_BYTES
            );
        }

        @Override
        protected FraudCaseLifecycleIdempotencyRecordDocument saveRecord(FraudCaseLifecycleIdempotencyRecordDocument record) {
            if (record.getStatus() == FraudCaseLifecycleIdempotencyStatus.COMPLETED) {
                throw new IllegalStateException("idempotency completion save failed");
            }
            return super.saveRecord(record);
        }
    }

    private static final class FailingFraudCaseAuditService extends FraudCaseAuditService {
        private FailingFraudCaseAuditService(FraudCaseAuditRepository auditRepository) {
            super(auditRepository);
        }

        @Override
        public FraudCaseAuditEntryDocument append(
                String caseId,
                String actorId,
                FraudCaseAuditAction action,
                FraudCaseStatus previousStatus,
                FraudCaseStatus newStatus,
                Map<String, String> details
        ) {
            throw new IllegalStateException("audit append failed");
        }
    }

    private static final class FixedTransactionManagerProvider implements ObjectProvider<PlatformTransactionManager> {
        private final PlatformTransactionManager transactionManager;

        private FixedTransactionManagerProvider(PlatformTransactionManager transactionManager) {
            this.transactionManager = transactionManager;
        }

        @Override
        public PlatformTransactionManager getObject(Object... args) throws BeansException {
            return transactionManager;
        }

        @Override
        public PlatformTransactionManager getIfAvailable() throws BeansException {
            return transactionManager;
        }

        @Override
        public PlatformTransactionManager getIfUnique() throws BeansException {
            return transactionManager;
        }

        @Override
        public PlatformTransactionManager getObject() throws BeansException {
            return transactionManager;
        }

        @Override
        public Iterator<PlatformTransactionManager> iterator() {
            return List.of(transactionManager).iterator();
        }

        @Override
        public Stream<PlatformTransactionManager> stream() {
            return Stream.of(transactionManager);
        }
    }
}
