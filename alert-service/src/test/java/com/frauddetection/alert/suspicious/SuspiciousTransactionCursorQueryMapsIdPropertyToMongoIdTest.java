package com.frauddetection.alert.suspicious;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SuspiciousTransactionCursorQueryMapsIdPropertyToMongoIdTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "fdp63_suspicious_mapping_proof");
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void cursorQueryAndSortMapSuspiciousTransactionIdPropertyToMongoId() {
        MappingMongoConverter converter = (MappingMongoConverter) mongoTemplate.getConverter();
        MongoPersistentEntity<?> entity = converter.getMappingContext()
                .getRequiredPersistentEntity(SuspiciousTransactionDocument.class);
        QueryMapper queryMapper = new QueryMapper(converter);

        Query query = new Query(new Criteria().orOperator(
                Criteria.where("detectedAt").lt(Instant.parse("2026-05-10T10:00:00Z")),
                new Criteria().andOperator(
                        Criteria.where("detectedAt").is(Instant.parse("2026-05-10T10:00:00Z")),
                        Criteria.where("suspiciousTransactionId").lt("suspicious-1")
                )
        )).with(Sort.by(
                Sort.Order.desc("detectedAt"),
                Sort.Order.desc("suspiciousTransactionId")
        ));

        Document mappedQuery = queryMapper.getMappedObject(query.getQueryObject(), entity);
        Document mappedSort = queryMapper.getMappedSort(query.getSortObject(), entity);

        assertThat(mappedQuery.toJson()).contains("\"_id\"");
        assertThat(mappedQuery.toJson()).doesNotContain("suspiciousTransactionId");
        assertThat(mappedSort).containsEntry("detectedAt", -1);
        assertThat(mappedSort).containsEntry("_id", -1);
        assertThat(mappedSort).doesNotContainKey("suspiciousTransactionId");
    }
}
