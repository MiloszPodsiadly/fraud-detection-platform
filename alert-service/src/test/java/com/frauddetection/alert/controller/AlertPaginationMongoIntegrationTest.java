package com.frauddetection.alert.controller;

import com.frauddetection.alert.persistence.AlertDocument;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AlertPaginationMongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "alert_pagination_test");
        mongoTemplate.dropCollection(AlertDocument.class);
        MongoPersistentEntityIndexResolver resolver = new MongoPersistentEntityIndexResolver(
                mongoTemplate.getConverter().getMappingContext()
        );
        resolver.resolveIndexFor(AlertDocument.class)
                .forEach(index -> mongoTemplate.indexOps(AlertDocument.class).ensureIndex(index));
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void tiedAlertTimestampsTraversePagesWithStableUniqueIdsAndAnUsableSortIndex() {
        Instant timestamp = Instant.parse("2026-11-07T10:00:00Z");
        for (String alertId : List.of("alert-a", "alert-c", "alert-e", "alert-b", "alert-d", "alert-f")) {
            AlertDocument alert = new AlertDocument();
            alert.setAlertId(alertId);
            alert.setTransactionId("txn-" + alertId);
            alert.setAlertTimestamp(timestamp);
            mongoTemplate.insert(alert);
        }
        Sort sort = Sort.by(Sort.Order.desc("alertTimestamp"), Sort.Order.desc("alertId"));
        List<String> expected = List.of("alert-f", "alert-e", "alert-d", "alert-c", "alert-b", "alert-a");
        List<String> traversed = new ArrayList<>();

        for (int page = 0; page < 3; page++) {
            traversed.addAll(mongoTemplate.find(
                            new Query().with(PageRequest.of(page, 2, sort)), AlertDocument.class
                    ).stream().map(AlertDocument::getAlertId).toList());
        }
        List<String> repeatedMiddlePage = mongoTemplate.find(
                        new Query().with(PageRequest.of(1, 2, sort)), AlertDocument.class
                ).stream().map(AlertDocument::getAlertId).toList();

        assertThat(traversed).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(repeatedMiddlePage).containsExactly("alert-d", "alert-c");
        Document winningPlan = mongoTemplate.getCollection("alerts").find()
                .sort(new Document("alertTimestamp", -1).append("_id", -1))
                .hintString("alerts_timestamp_id_idx")
                .explain()
                .get("queryPlanner", Document.class)
                .get("winningPlan", Document.class);
        assertThat(winningPlan.toJson()).contains("IXSCAN", "alerts_timestamp_id_idx")
                .doesNotContain("\"stage\": \"SORT\"");
    }
}
