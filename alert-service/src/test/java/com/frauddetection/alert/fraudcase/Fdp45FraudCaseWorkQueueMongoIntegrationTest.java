package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.common.events.enums.RiskLevel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class Fdp45FraudCaseWorkQueueMongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private MongoFraudCaseSearchRepository repository;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "fdp45_work_queue");
        mongoTemplate.remove(new Query(), FraudCaseDocument.class);
        repository = new MongoFraudCaseSearchRepository(mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void shouldApplyFiltersStableSortPaginationAndTieBreakerAgainstMongo() {
        mongoTemplate.save(caseDocument("case-b", "FC-B", "2026-05-10T10:00:00Z", "2026-05-10T11:00:00Z", FraudCaseStatus.OPEN, "investigator-1"));
        mongoTemplate.save(caseDocument("case-a", "FC-A", "2026-05-10T10:00:00Z", "2026-05-10T11:30:00Z", FraudCaseStatus.OPEN, "investigator-1"));
        mongoTemplate.save(caseDocument("case-c", "FC-C", "2026-05-10T12:00:00Z", "2026-05-10T12:30:00Z", FraudCaseStatus.OPEN, "investigator-1"));
        mongoTemplate.save(caseDocument("case-noise", "FC-N", "2026-05-10T13:00:00Z", "2026-05-10T13:30:00Z", FraudCaseStatus.CLOSED, "investigator-2"));

        FraudCaseSearchCriteria criteria = new FraudCaseSearchCriteria(
                FraudCaseStatus.OPEN,
                "investigator-1",
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                Instant.parse("2026-05-10T09:00:00Z"),
                Instant.parse("2026-05-10T13:00:00Z"),
                Instant.parse("2026-05-10T10:30:00Z"),
                Instant.parse("2026-05-10T13:00:00Z"),
                "alert-1"
        );

        var first = repository.searchSlice(criteria, PageRequest.of(0, 2, Sort.by(Sort.Order.desc("createdAt"))));
        var second = repository.searchSlice(criteria, PageRequest.of(1, 2, Sort.by(Sort.Order.desc("createdAt"))));

        assertThat(first.getContent()).extracting(FraudCaseDocument::getCaseId)
                .containsExactly("case-c", "case-a");
        assertThat(first.hasNext()).isTrue();
        assertThat(second.getContent()).extracting(FraudCaseDocument::getCaseId)
                .containsExactly("case-b");
        assertThat(second.hasNext()).isFalse();
    }

    private FraudCaseDocument caseDocument(
            String caseId,
            String caseNumber,
            String createdAt,
            String updatedAt,
            FraudCaseStatus status,
            String investigator
    ) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(caseId);
        document.setCaseKey("key-" + caseId);
        document.setCaseNumber(caseNumber);
        document.setStatus(status);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setAssignedInvestigatorId(investigator);
        document.setLinkedAlertIds(List.of("alert-1"));
        document.setCreatedAt(Instant.parse(createdAt));
        document.setUpdatedAt(Instant.parse(updatedAt));
        return document;
    }
}
