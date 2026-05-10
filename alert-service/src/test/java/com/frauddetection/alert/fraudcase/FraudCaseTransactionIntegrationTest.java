package com.frauddetection.alert.fraudcase;

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
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.FraudCaseManagementService;
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

    private FraudCaseDocument createCase() {
        return service.createCase(new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        ));
    }

    private FraudCaseManagementService service(FraudCaseAuditService auditService) {
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        return new FraudCaseManagementService(
                caseRepository,
                new MongoRepositoryFactory(mongoTemplate).getRepository(ScoredTransactionRepository.class),
                alertRepository,
                noteRepository,
                decisionRepository,
                auditRepository,
                new MongoFraudCaseSearchRepository(mongoTemplate),
                new AnalystActorResolver(new CurrentAnalystUser(), metrics),
                metrics,
                new FraudCaseUpdateMutationHandler(caseRepository, metrics),
                unusedRegulatedMutationCoordinator(),
                transactionRunner(),
                new FraudCaseTransitionPolicy(),
                auditService,
                new FraudCaseResponseMapper(new AlertResponseMapper())
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
