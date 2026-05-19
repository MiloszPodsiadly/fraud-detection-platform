package com.frauddetection.alert.suspicious;

import com.frauddetection.common.events.enums.RiskLevel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.ALERT_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.CUSTOMER_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.CURSOR_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.IDEMPOTENCY_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.RISK_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.STATUS_INDEX;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SuspiciousTransactionMongoIndexIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "fdp63_suspicious_index_proof");
        mongoTemplate.dropCollection(SuspiciousTransactionDocument.class);
        ensureDeclaredIndexes();
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void springDataCreatesExpectedSuspiciousTransactionIndexesInMongo() {
        Map<String, IndexInfo> indexesByName = indexInfoByName();

        assertThat(indexesByName).containsKeys(
                "_id_",
                CURSOR_INDEX,
                STATUS_INDEX,
                RISK_INDEX,
                CUSTOMER_INDEX,
                ALERT_INDEX,
                IDEMPOTENCY_INDEX
        );
        assertThat(keys(indexesByName.get(CURSOR_INDEX))).containsExactly(
                entry("detectedAt", Sort.Direction.DESC),
                entry("_id", Sort.Direction.DESC)
        );
        assertThat(keys(indexesByName.get(STATUS_INDEX))).containsExactly(
                entry("status", Sort.Direction.ASC),
                entry("detectedAt", Sort.Direction.DESC),
                entry("_id", Sort.Direction.DESC)
        );
        assertThat(keys(indexesByName.get(RISK_INDEX))).containsExactly(
                entry("riskLevel", Sort.Direction.ASC),
                entry("detectedAt", Sort.Direction.DESC),
                entry("_id", Sort.Direction.DESC)
        );
        assertThat(keys(indexesByName.get(CUSTOMER_INDEX))).containsExactly(
                entry("customerId", Sort.Direction.ASC),
                entry("detectedAt", Sort.Direction.DESC),
                entry("_id", Sort.Direction.DESC)
        );
        assertThat(keys(indexesByName.get(ALERT_INDEX))).containsExactly(
                entry("linkedAlertId", Sort.Direction.ASC),
                entry("detectedAt", Sort.Direction.DESC),
                entry("_id", Sort.Direction.DESC)
        );
        assertThat(keys(indexesByName.get(IDEMPOTENCY_INDEX))).containsExactly(
                entry("transactionId", Sort.Direction.ASC),
                entry("sourceEventId", Sort.Direction.ASC)
        );
        assertThat(indexesByName.get(IDEMPOTENCY_INDEX).isUnique()).isTrue();
    }

    @Test
    void representativeCursorQueryCanUseDeclaredCursorIndexHint() {
        mongoTemplate.save(document("suspicious-c", "txn-c", "event-c", "2026-05-10T10:00:00Z"));
        mongoTemplate.save(document("suspicious-b", "txn-b", "event-b", "2026-05-10T10:00:00Z"));
        mongoTemplate.save(document("suspicious-a", "txn-a", "event-a", "2026-05-10T09:00:00Z"));

        Query query = new Query(new Criteria().orOperator(
                Criteria.where("detectedAt").lt(Instant.parse("2026-05-10T10:00:00Z")),
                new Criteria().andOperator(
                        Criteria.where("detectedAt").is(Instant.parse("2026-05-10T10:00:00Z")),
                        Criteria.where("suspiciousTransactionId").lt("suspicious-c")
                )
        ))
                .with(Sort.by(
                        Sort.Order.desc("detectedAt"),
                        Sort.Order.desc("suspiciousTransactionId")
                ))
                .withHint(CURSOR_INDEX)
                .limit(2);

        assertThat(mongoTemplate.find(query, SuspiciousTransactionDocument.class))
                .extracting(SuspiciousTransactionDocument::getSuspiciousTransactionId)
                .containsExactly("suspicious-b", "suspicious-a");
    }

    private void ensureDeclaredIndexes() {
        MongoPersistentEntityIndexResolver resolver = new MongoPersistentEntityIndexResolver(
                mongoTemplate.getConverter().getMappingContext()
        );
        resolver.resolveIndexFor(SuspiciousTransactionDocument.class)
                .forEach(index -> mongoTemplate.indexOps(SuspiciousTransactionDocument.class).ensureIndex(index));
    }

    private Map<String, IndexInfo> indexInfoByName() {
        return mongoTemplate.indexOps(SuspiciousTransactionDocument.class)
                .getIndexInfo()
                .stream()
                .collect(Collectors.toMap(IndexInfo::getName, Function.identity()));
    }

    private LinkedHashMap<String, Sort.Direction> keys(IndexInfo indexInfo) {
        LinkedHashMap<String, Sort.Direction> keys = new LinkedHashMap<>();
        indexInfo.getIndexFields().forEach(field -> keys.put(field.getKey(), field.getDirection()));
        return keys;
    }

    private Map.Entry<String, Sort.Direction> entry(String key, Sort.Direction direction) {
        return Map.entry(key, direction);
    }

    private SuspiciousTransactionDocument document(
            String suspiciousTransactionId,
            String transactionId,
            String sourceEventId,
            String detectedAt
    ) {
        SuspiciousTransactionDocument document = new SuspiciousTransactionDocument();
        document.setSuspiciousTransactionId(suspiciousTransactionId);
        document.setTransactionId(transactionId);
        document.setSourceEventId(sourceEventId);
        document.setCustomerId("customer-1");
        document.setRiskLevel(RiskLevel.HIGH);
        document.setStatus(SuspiciousTransactionStatus.NEW);
        document.setDetectedAt(Instant.parse(detectedAt));
        return document;
    }
}
