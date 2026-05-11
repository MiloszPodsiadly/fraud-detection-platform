package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.AssignFraudCaseRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
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
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDecisionRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.FraudCaseTransactionDocument;
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
import com.frauddetection.common.events.model.Money;
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
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class Fdp44FraudCaseLifecycleReplayEquivalenceIntegrationTest extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

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
                FraudPlatformContainers.mongodb().getReplicaSetUrl("fdp44_replay_equivalence_" + UUID.randomUUID().toString().replace("-", ""))
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        caseRepository = repositoryFactory.getRepository(FraudCaseRepository.class);
        noteRepository = repositoryFactory.getRepository(FraudCaseNoteRepository.class);
        decisionRepository = repositoryFactory.getRepository(FraudCaseDecisionRepository.class);
        auditRepository = repositoryFactory.getRepository(FraudCaseAuditRepository.class);
        idempotencyRepository = repositoryFactory.getRepository(FraudCaseLifecycleIdempotencyRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        alertRepository.save(alert("alert-1"));
        service = service(repositoryFactory);
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
    void createCaseFirstResponseJsonEqualsReplayResponseJson() {
        CreateFraudCaseRequest request = new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        );

        FraudCaseResponse first = service.createCase(request, "create-equivalence-key");
        FraudCaseResponse replay = service.createCase(request, "create-equivalence-key");

        assertJsonEqual(first, replay);
    }

    @Test
    void assignCaseFirstResponseJsonEqualsReplayResponseJson() {
        FraudCaseDocument document = richCase("assign-case", FraudCaseStatus.OPEN);
        AssignFraudCaseRequest request = new AssignFraudCaseRequest("investigator-replay", "lead-1");

        FraudCaseResponse first = service.assignCase(document.getCaseId(), request, "assign-equivalence-key");
        FraudCaseResponse replay = service.assignCase(document.getCaseId(), request, "assign-equivalence-key");

        assertJsonEqual(first, replay);
        assertRichCaseFields(first);
    }

    @Test
    void transitionCaseFirstResponseJsonEqualsReplayResponseJson() {
        FraudCaseDocument document = richCase("transition-case", FraudCaseStatus.OPEN);
        TransitionFraudCaseRequest request = new TransitionFraudCaseRequest(FraudCaseStatus.IN_REVIEW, "analyst-1");

        FraudCaseResponse first = service.transitionCase(document.getCaseId(), request, "transition-equivalence-key");
        FraudCaseResponse replay = service.transitionCase(document.getCaseId(), request, "transition-equivalence-key");

        assertJsonEqual(first, replay);
        assertRichCaseFields(first);
    }

    @Test
    void closeCaseFirstResponseJsonEqualsReplayResponseJson() {
        FraudCaseDocument document = richCase("close-case", FraudCaseStatus.RESOLVED);
        CloseFraudCaseRequest request = new CloseFraudCaseRequest("Resolved with full context", "lead-1");

        FraudCaseResponse first = service.closeCase(document.getCaseId(), request, "close-equivalence-key");
        FraudCaseResponse replay = service.closeCase(document.getCaseId(), request, "close-equivalence-key");

        assertJsonEqual(first, replay);
        assertRichCaseFields(first);
        JsonNode firstJson = objectMapper.valueToTree(first);
        JsonNode replayJson = objectMapper.valueToTree(replay);
        assertThat(firstJson.get("closedAt").asText()).isNotBlank();
        assertThat(firstJson.get("closureReason").asText()).isEqualTo("Resolved with full context");
        assertThat(replayJson.get("closedAt")).isEqualTo(firstJson.get("closedAt"));
        assertThat(replayJson.get("closureReason")).isEqualTo(firstJson.get("closureReason"));
    }

    @Test
    void reopenCaseFirstResponseJsonEqualsReplayResponseJson() {
        FraudCaseDocument document = richCase("reopen-case", FraudCaseStatus.CLOSED);
        document.setClosedAt(Instant.parse("2026-05-11T10:30:00Z"));
        document.setClosureReason("Resolved with full context");
        caseRepository.save(document);
        ReopenFraudCaseRequest request = new ReopenFraudCaseRequest("New evidence", "lead-1");

        FraudCaseResponse first = service.reopenCase(document.getCaseId(), request, "reopen-equivalence-key");
        FraudCaseResponse replay = service.reopenCase(document.getCaseId(), request, "reopen-equivalence-key");

        assertJsonEqual(first, replay);
        assertRichCaseFields(first);
        JsonNode firstJson = objectMapper.valueToTree(first);
        JsonNode replayJson = objectMapper.valueToTree(replay);
        assertThat(firstJson.get("closedAt").isNull()).isTrue();
        assertThat(firstJson.get("closureReason").isNull()).isTrue();
        assertThat(replayJson.get("closedAt").isNull()).isTrue();
        assertThat(replayJson.get("closureReason").isNull()).isTrue();
    }

    @Test
    void addNoteFirstResponseJsonEqualsReplayResponseJson() {
        FraudCaseDocument document = richCase("note-case", FraudCaseStatus.OPEN);
        AddFraudCaseNoteRequest request = new AddFraudCaseNoteRequest("Replay-visible note", true, "analyst-1");

        var first = service.addNote(document.getCaseId(), request, "note-equivalence-key");
        var replay = service.addNote(document.getCaseId(), request, "note-equivalence-key");

        assertJsonEqual(first, replay);
        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(document.getCaseId())).hasSize(1);
    }

    @Test
    void addDecisionFirstResponseJsonEqualsReplayResponseJson() {
        FraudCaseDocument document = richCase("decision-case", FraudCaseStatus.OPEN);
        AddFraudCaseDecisionRequest request = new AddFraudCaseDecisionRequest(
                FraudCaseDecisionType.FRAUD_CONFIRMED,
                "Confirmed with replay proof",
                "analyst-1"
        );

        var first = service.addDecision(document.getCaseId(), request, "decision-equivalence-key");
        var replay = service.addDecision(document.getCaseId(), request, "decision-equivalence-key");

        assertJsonEqual(first, replay);
        assertThat(decisionRepository.findByCaseIdOrderByCreatedAtAsc(document.getCaseId())).hasSize(1);
    }

    private void assertJsonEqual(Object first, Object replay) {
        JsonNode firstJson = objectMapper.valueToTree(first);
        JsonNode replayJson = objectMapper.valueToTree(replay);
        assertThat(replayJson).isEqualTo(firstJson);
    }

    private void assertRichCaseFields(FraudCaseResponse response) {
        JsonNode json = objectMapper.valueToTree(response);
        // This test is intentionally broad. If FraudCaseResponse gains a new public field,
        // add a populated assertion here so replay equivalence remains regulator-defensible.
        assertNoMissingRichFields(json);
        assertThat(json.get("caseId").asText()).isNotBlank();
        assertThat(json.get("caseNumber").asText()).startsWith("FC-20260511-");
        assertThat(json.get("customerId").asText()).isEqualTo("customer-rich");
        assertThat(json.get("suspicionType").asText()).isEqualTo("RAPID_TRANSFER_BURST_20K_PLN");
        assertThat(json.get("status").asText()).isNotBlank();
        assertThat(json.get("priority").asText()).isEqualTo("HIGH");
        assertThat(json.get("riskLevel").asText()).isEqualTo("CRITICAL");
        assertThat(json.get("linkedAlertIds")).hasSize(1);
        assertThat(json.get("createdBy").asText()).isEqualTo("system");
        assertThat(json.get("reason").asText()).isEqualTo("Multiple transfers exceeded threshold.");
        assertThat(json.get("thresholdPln").decimalValue()).isEqualByComparingTo("20000.00");
        assertThat(json.get("totalAmountPln").decimalValue()).isEqualByComparingTo("42500.25");
        assertThat(json.get("aggregationWindow").asText()).isEqualTo("PT5M");
        assertThat(json.get("firstTransactionAt").asText()).isEqualTo("2026-05-11T09:55:00Z");
        assertThat(json.get("lastTransactionAt").asText()).isEqualTo("2026-05-11T10:00:00Z");
        assertThat(json.get("createdAt").asText()).isEqualTo("2026-05-11T09:54:00Z");
        assertThat(json.get("updatedAt").asText()).isNotBlank();
        assertThat(json.get("analystId").asText()).isEqualTo("analyst-decision");
        assertThat(json.get("decisionReason").asText()).isEqualTo("Manual review confirmed pattern");
        assertThat(json.get("decisionTags")).hasSize(2);
        assertThat(json.get("decidedAt").asText()).isEqualTo("2026-05-11T10:01:00Z");
        assertThat(json.get("version").isNumber()).isTrue();
        assertThat(json.get("transactionIds")).hasSize(2);
        assertThat(json.get("transactions")).hasSize(1);
        JsonNode transaction = json.get("transactions").get(0);
        assertThat(transaction.get("transactionId").asText()).isEqualTo("tx-1");
        assertThat(transaction.get("correlationId").asText()).isEqualTo("corr-1");
        assertThat(transaction.get("transactionTimestamp").asText()).isEqualTo("2026-05-11T09:55:00Z");
        assertThat(transaction.get("transactionAmount").get("amount").decimalValue()).isEqualByComparingTo("10000.10");
        assertThat(transaction.get("transactionAmount").get("currency").asText()).isEqualTo("EUR");
        assertThat(transaction.get("amountPln").decimalValue()).isEqualByComparingTo("43000.43");
        assertThat(transaction.get("fraudScore").doubleValue()).isEqualTo(0.97d);
        assertThat(transaction.get("riskLevel").asText()).isEqualTo("CRITICAL");
    }

    private void assertNoMissingRichFields(JsonNode json) {
        assertThat(json.fieldNames())
                .toIterable()
                .contains(
                        "caseId",
                        "caseNumber",
                        "customerId",
                        "suspicionType",
                        "status",
                        "priority",
                        "riskLevel",
                        "linkedAlertIds",
                        "assignedInvestigatorId",
                        "createdBy",
                        "reason",
                        "thresholdPln",
                        "totalAmountPln",
                        "aggregationWindow",
                        "firstTransactionAt",
                        "lastTransactionAt",
                        "createdAt",
                        "updatedAt",
                        "analystId",
                        "decisionReason",
                        "decisionTags",
                        "decidedAt",
                        "closedAt",
                        "closureReason",
                        "version",
                        "transactionIds",
                        "transactions"
                );
    }

    private FraudCaseDocument richCase(String caseId, FraudCaseStatus status) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(caseId);
        document.setCaseKey("FDP44:" + caseId);
        document.setCaseNumber("FC-20260511-" + caseId.toUpperCase(java.util.Locale.ROOT).substring(0, 8));
        document.setCustomerId("customer-rich");
        document.setSuspicionType("RAPID_TRANSFER_BURST_20K_PLN");
        document.setStatus(status);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setLinkedAlertIds(List.of("alert-1"));
        document.setAssignedInvestigatorId("investigator-original");
        document.setCreatedBy("system");
        document.setReason("Multiple transfers exceeded threshold.");
        document.setThresholdPln(new BigDecimal("20000.00"));
        document.setTotalAmountPln(new BigDecimal("42500.25"));
        document.setAggregationWindow("PT5M");
        document.setFirstTransactionAt(Instant.parse("2026-05-11T09:55:00Z"));
        document.setLastTransactionAt(Instant.parse("2026-05-11T10:00:00Z"));
        document.setCreatedAt(Instant.parse("2026-05-11T09:54:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-11T10:00:00Z"));
        document.setAnalystId("analyst-decision");
        document.setDecisionReason("Manual review confirmed pattern");
        document.setDecisionTags(List.of("rapid-transfer", "high-value"));
        document.setDecidedAt(Instant.parse("2026-05-11T10:01:00Z"));
        document.setTransactionIds(List.of("tx-1", "tx-2"));
        document.setTransactions(List.of(transaction()));
        return caseRepository.save(document);
    }

    private FraudCaseTransactionDocument transaction() {
        FraudCaseTransactionDocument transaction = new FraudCaseTransactionDocument();
        transaction.setTransactionId("tx-1");
        transaction.setCorrelationId("corr-1");
        transaction.setTransactionTimestamp(Instant.parse("2026-05-11T09:55:00Z"));
        transaction.setTransactionAmount(new Money(new BigDecimal("10000.10"), "EUR"));
        transaction.setAmountPln(new BigDecimal("43000.43"));
        transaction.setFraudScore(0.97d);
        transaction.setRiskLevel(RiskLevel.CRITICAL);
        return transaction;
    }

    private FraudCaseManagementService service(MongoRepositoryFactory repositoryFactory) {
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        var transactionRunner = transactionRunner();
        var responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        var actorResolver = new AnalystActorResolver(new CurrentAnalystUser(), metrics);
        var idempotencyService = new FraudCaseLifecycleIdempotencyService(
                idempotencyRepository,
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES,
                FraudCaseLifecycleIdempotencyService.DEFAULT_RETENTION,
                metrics,
                java.time.Clock.systemUTC()
        );
        return new FraudCaseManagementService(
                caseRepository,
                repositoryFactory.getRepository(ScoredTransactionRepository.class),
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
                        new FraudCaseAuditService(auditRepository),
                        idempotencyService,
                        responseMapper
                ),
                new FraudCaseQueryService(
                        caseRepository,
                        auditRepository,
                        new MongoFraudCaseSearchRepository(mongoTemplate),
                        responseMapper,
                        new FraudCaseWorkQueueProperties(java.time.Duration.ofHours(24))
                )
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
                throw new AssertionError("FDP-44 replay equivalence tests must not use RegulatedMutationCoordinator.");
            }
        };
    }

    private AlertDocument alert(String alertId) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        document.setTransactionId(alertId + "-transaction");
        document.setCreatedAt(Instant.parse("2026-05-11T09:50:00Z"));
        return document;
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
