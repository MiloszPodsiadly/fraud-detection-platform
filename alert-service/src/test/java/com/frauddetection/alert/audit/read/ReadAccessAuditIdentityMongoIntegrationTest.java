package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class ReadAccessAuditIdentityMongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongo;
    private ReadAccessAuditRepository repository;
    private CurrentAnalystUser currentAnalystUser;
    private ReadAccessAuditService service;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongo = new MongoTemplate(mongoClient, "read_access_actor_identity_test");
        mongo.dropCollection(ReadAccessAuditEventDocument.class);
        repository = new MongoRepositoryFactory(mongo).getRepository(ReadAccessAuditRepository.class);
        currentAnalystUser = mock(CurrentAnalystUser.class);
        service = new ReadAccessAuditService(repository, currentAnalystUser,
                new AlertServiceMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void persistedActorIdsAreStableAndDistinctAcrossIssuersAndSubjects() {
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(ReadAccessEndpointCategory.ALERT_DETAIL,
                ReadAccessResourceType.ALERT, "alert-1", null, null, null, null);
        auditAs("https://issuer-a.example", "shared-subject", target, "corr-1");
        auditAs("https://issuer-b.example", "shared-subject", target, "corr-2");
        auditAs("https://issuer-a.example", "other-subject", target, "corr-3");
        auditAs("https://issuer-a.example", "shared-subject", target, "corr-4");

        List<ReadAccessAuditEventDocument> events = mongo.findAll(ReadAccessAuditEventDocument.class).stream()
                .sorted(java.util.Comparator.comparing(ReadAccessAuditEventDocument::correlationId))
                .toList();
        assertThat(events).hasSize(4);
        List<String> actorIds = events.stream().map(ReadAccessAuditEventDocument::actorId).toList();
        assertThat(actorIds).containsOnly(
                PolicyGovernanceActor.identityId("https://issuer-a.example", "shared-subject"),
                PolicyGovernanceActor.identityId("https://issuer-b.example", "shared-subject"),
                PolicyGovernanceActor.identityId("https://issuer-a.example", "other-subject"));
        assertThat(actorIds.get(0)).isEqualTo(actorIds.get(3));
        assertThat(actorIds.get(0)).isNotEqualTo(actorIds.get(1)).isNotEqualTo(actorIds.get(2));
        assertThat(actorIds.get(1)).isNotEqualTo(actorIds.get(2));
        assertThat(events.toString()).doesNotContain("shared-subject", "other-subject");
    }

    private void auditAs(String issuer, String subject, ReadAccessAuditTarget target, String correlationId) {
        when(currentAnalystUser.get()).thenReturn(Optional.of(new AnalystPrincipal(
                subject, Set.of(), Set.of(), issuer + "#" + subject)));
        service.auditOrThrow(target, ReadAccessAuditOutcome.SUCCESS, 1, correlationId);
    }
}
