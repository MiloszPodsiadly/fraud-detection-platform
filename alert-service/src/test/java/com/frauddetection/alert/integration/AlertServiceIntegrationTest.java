package com.frauddetection.alert.integration;

import com.frauddetection.alert.audit.AuditChainIndexInitializer;
import com.frauddetection.alert.audit.read.ReadAccessAuditEventDocument;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.security.auth.DemoAuthHeaders;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.common.testsupport.container.TestContainerProperties;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.demo-auth.enabled=true",
        "app.sensitive-reads.audit.fail-closed=true"
})
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class AlertServiceIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaTemplate<String, TransactionScoredEvent> transactionScoredEventKafkaTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private AuditChainIndexInitializer auditChainIndexInitializer;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        var backendProperties = TestContainerProperties.backendProperties();
        String mongoUri = FraudPlatformContainers.mongodb().getReplicaSetUrl("alert_service_test");
        backendProperties.put("spring.mongodb.uri", mongoUri);
        backendProperties.put("spring.data.mongodb.uri", mongoUri);
        backendProperties.forEach((key, value) -> registry.add(key, () -> value));
        registry.add("app.kafka.topics.transaction-scored", () -> "transactions.scored.alert.test");
        registry.add("app.kafka.topics.fraud-alerts", () -> "fraud.alerts.test");
        registry.add("app.kafka.topics.fraud-decisions", () -> "fraud.decisions.test");
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dead-letter.alert.test");
        registry.add("app.model-promotion.persistence.enabled", () -> "false");
    }

    @Test
    void shouldStartContextWithMongoAndKafka() {
        assertThat(auditChainIndexInitializer.preflight().ready()).isTrue();
        var eventIndexes = mongoTemplate.getCollection("audit_events").listIndexes(Document.class)
                .into(new java.util.ArrayList<>()).stream()
                .toList();
        var anchorIndexes = mongoTemplate.getCollection("audit_chain_anchors").listIndexes(Document.class)
                .into(new java.util.ArrayList<>()).stream()
                .toList();
        System.out.println("audit_events.listIndexes()=" + eventIndexes);
        System.out.println("audit_chain_anchors.listIndexes()=" + anchorIndexes);
        var requestIndexes = eventIndexes.stream()
                .filter(index -> index.get("key", Document.class).equals(new Document("request_id", 1)))
                .toList();
        assertThat(requestIndexes).singleElement().satisfies(index -> {
            assertThat(index.getBoolean("unique")).isTrue();
            assertThat(index.getBoolean("sparse")).isNotEqualTo(true);
            assertThat(index.get("partialFilterExpression"))
                    .isEqualTo(new Document("request_id", new Document("$type", "string")));
        });
        transactionScoredEventKafkaTemplate.send("transactions.scored.alert.test", "txn-1", TransactionFixtures.scoredTransaction().build());
        transactionScoredEventKafkaTemplate.flush();
        assertThat(new RestTemplate()).isNotNull();
    }

    @Test
    void embeddedHttpAlertListPersistsOneBoundedReadAuditBeforeReturningContent() throws Exception {
        String correlationId = "alert-read-http-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.set(DemoAuthHeaders.USER_ID, "alert-audit-http-analyst");
        headers.set(DemoAuthHeaders.ROLES, "FRAUD_OPS_ADMIN");
        headers.set("X-Correlation-Id", correlationId);

        var response = new RestTemplate().exchange(
                "http://localhost:" + port + "/api/v1/alerts?page=0&size=5",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("content").isArray()).isTrue();
        List<ReadAccessAuditEventDocument> events = mongoTemplate.find(
                Query.query(Criteria.where("correlation_id").is(correlationId)),
                ReadAccessAuditEventDocument.class
        );
        assertThat(events).hasSize(1);
        ReadAccessAuditEventDocument event = events.getFirst();
        assertThat(event.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.ALERT_LIST);
        assertThat(event.resourceType()).isEqualTo(ReadAccessResourceType.ALERT);
        assertThat(event.actorId()).startsWith("local:");
        assertThat(event.resultCount()).isEqualTo(body.path("content").size());
        assertThat(event.page()).isZero();
        assertThat(event.size()).isEqualTo(5);
        assertThat(event.queryHash()).matches("[0-9a-f]{32}");
        assertThat(event.toString()).doesNotContain("customerIds", "transactionIds", "narrative");
    }
}
