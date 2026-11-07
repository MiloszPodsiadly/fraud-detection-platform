package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionProjectionWriter;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class TransactionMonitoringServiceSearchWindowMongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private TransactionMonitoringService service;
    private ScoredTransactionRepository repository;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "scored_transaction_search_window_test");
        mongoTemplate.dropCollection(ScoredTransactionDocument.class);
        ensureDeclaredIndexes();
        repository = mock(ScoredTransactionRepository.class);
        when(repository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            List<ScoredTransactionDocument> documents = mongoTemplate.find(new Query().with(pageable), ScoredTransactionDocument.class);
            return new PageImpl<>(documents, pageable, mongoTemplate.count(new Query(), ScoredTransactionDocument.class));
        });
        ScoredTransactionDocumentMapper mapper = mock(ScoredTransactionDocumentMapper.class);
        when(mapper.toDomain(any(ScoredTransactionDocument.class))).thenAnswer(invocation -> {
            ScoredTransactionDocument document = invocation.getArgument(0);
            return new ScoredTransaction(
                    document.getTransactionId(), null, null, null, document.getScoredAt(), null, null,
                    null, null, null, List.of(), null,
                    ScoringOccurrenceOwnership.authoritative(
                            "event-" + document.getTransactionId(), document.getScoredAt(), "a".repeat(64)
                    ),
                    MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
            );
        });
        service = new TransactionMonitoringService(
                repository, mapper, mongoTemplate,
                new ScoredTransactionSearchPolicy(), mock(ScoredTransactionProjectionWriter.class)
        );
    }

    @AfterEach
    void tearDown() {
        mongoClient.close();
    }

    @Test
    void mongoFilteredWindowCapsRowsAndPageImplTotalsAtTenThousand() {
        insertMatches(25_000);

        var lastFullPage = service.listScoredTransactions(
                PageRequest.of(399, 25, Sort.by(Sort.Direction.DESC, "scoredAt")),
                new ScoredTransactionSearchCriteria("match", null, null)
        );
        var beyondWindow = service.listScoredTransactions(
                PageRequest.of(400, 25, Sort.by(Sort.Direction.DESC, "scoredAt")),
                new ScoredTransactionSearchCriteria("match", null, null)
        );
        var partialNonDivisorPage = service.listScoredTransactions(
                PageRequest.of(196, 51, Sort.by(Sort.Direction.DESC, "scoredAt")),
                new ScoredTransactionSearchCriteria("match", null, null)
        );

        assertThat(lastFullPage.page().getContent()).hasSize(25);
        assertThat(lastFullPage.page().getTotalElements()).isEqualTo(10_000);
        assertThat(lastFullPage.page().getTotalPages()).isEqualTo(400);
        assertThat(lastFullPage.totalElementsCapped()).isTrue();
        assertThat(beyondWindow.page().getContent()).isEmpty();
        assertThat(beyondWindow.page().getTotalElements()).isEqualTo(10_000);
        assertThat(beyondWindow.totalElementsCapped()).isTrue();
        assertThat(partialNonDivisorPage.page().getContent()).hasSize(4);
        assertThat(partialNonDivisorPage.page().getTotalPages()).isEqualTo(197);
        assertThat(partialNonDivisorPage.totalElementsCapped()).isTrue();
    }

    @Test
    void exactTenThousandMongoMatchesRemainMarkedAsExact() {
        insertMatches(10_000);

        var result = service.listScoredTransactions(
                PageRequest.of(399, 25, Sort.by(Sort.Direction.DESC, "scoredAt")),
                new ScoredTransactionSearchCriteria("match", null, null)
        );

        assertThat(result.page().getContent()).hasSize(25);
        assertThat(result.page().getTotalElements()).isEqualTo(10_000);
        assertThat(result.totalElementsCapped()).isFalse();
    }

    @Test
    void tiedTimestampsHaveStableUniqueOrderAcrossFilteredAndUnfilteredPages() {
        Instant tiedTimestamp = Instant.parse("2026-11-07T10:00:00Z");
        for (String transactionId : List.of("tie-a", "tie-c", "tie-e", "tie-b", "tie-d", "tie-f")) {
            ScoredTransactionDocument document = new ScoredTransactionDocument();
            document.setTransactionId(transactionId);
            document.setTransactionIdSearch(transactionId);
            document.setScoredAt(tiedTimestamp);
            mongoTemplate.insert(document);
        }
        Sort sort = Sort.by(Sort.Order.desc("scoredAt"), Sort.Order.desc("transactionId"));
        List<String> expected = List.of("tie-f", "tie-e", "tie-d", "tie-c", "tie-b", "tie-a");

        List<String> filtered = new ArrayList<>();
        List<String> repeatedMiddlePage = service.listScoredTransactions(
                        PageRequest.of(1, 2, sort), new ScoredTransactionSearchCriteria("tie", null, null)
                ).page().getContent().stream().map(ScoredTransaction::transactionId).toList();
        for (int page = 0; page < 3; page++) {
            filtered.addAll(service.listScoredTransactions(
                            PageRequest.of(page, 2, sort), new ScoredTransactionSearchCriteria("tie", null, null)
                    ).page().getContent().stream().map(ScoredTransaction::transactionId).toList());
        }

        List<String> unfiltered = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            unfiltered.addAll(service.listScoredTransactions(PageRequest.of(page, 2, sort)).getContent().stream()
                    .map(ScoredTransaction::transactionId).toList());
        }

        assertThat(filtered).containsExactlyElementsOf(expected);
        assertThat(unfiltered).containsExactlyElementsOf(expected);
        assertThat(repeatedMiddlePage).containsExactly("tie-d", "tie-c");
        assertThat(filtered).doesNotHaveDuplicates();
        assertThat(unfiltered).doesNotHaveDuplicates();
        assertSortIndexIsUsable("scored_transactions", "scored_transactions_scored_at_id_idx",
                new Document("scoredAt", -1).append("_id", -1));
    }

    private void insertMatches(int count) {
        List<ScoredTransactionDocument> documents = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ScoredTransactionDocument document = new ScoredTransactionDocument();
            document.setTransactionId("match-%05d".formatted(index));
            document.setTransactionIdSearch("match-%05d".formatted(index));
            document.setScoredAt(Instant.ofEpochSecond(index));
            documents.add(document);
        }
        mongoTemplate.insertAll(documents);
    }

    private void ensureDeclaredIndexes() {
        MongoPersistentEntityIndexResolver resolver = new MongoPersistentEntityIndexResolver(
                mongoTemplate.getConverter().getMappingContext()
        );
        resolver.resolveIndexFor(ScoredTransactionDocument.class)
                .forEach(index -> mongoTemplate.indexOps(ScoredTransactionDocument.class).ensureIndex(index));
    }

    private void assertSortIndexIsUsable(String collection, String indexName, Document sort) {
        Document winningPlan = mongoTemplate.getCollection(collection).find().sort(sort).hintString(indexName).explain()
                .get("queryPlanner", Document.class).get("winningPlan", Document.class);
        assertThat(winningPlan.toJson()).contains("IXSCAN", indexName).doesNotContain("\"stage\": \"SORT\"");
    }
}
