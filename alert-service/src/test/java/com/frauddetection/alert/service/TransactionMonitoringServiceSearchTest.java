package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionProjectionWriter;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.enums.RiskLevel;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionMonitoringServiceSearchTest {

    private final ScoredTransactionRepository repository = mock(ScoredTransactionRepository.class);
    private final ScoredTransactionDocumentMapper mapper = mock(ScoredTransactionDocumentMapper.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final ScoredTransactionSearchPolicy searchPolicy = new ScoredTransactionSearchPolicy();
    private final TransactionMonitoringService service = new TransactionMonitoringService(
            repository,
            mapper,
            mongoTemplate,
            searchPolicy,
            mock(ScoredTransactionProjectionWriter.class)
    );

    @Test
    void shouldUseBoundedCountProbeForFilteredSearch() {
        ScoredTransactionDocument document = document("txn-1");
        ScoredTransaction domain = new ScoredTransaction(
                "txn-1",
                "customer-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                ScoringOccurrenceOwnership.authoritative(
                        "event-1",
                        Instant.parse("2026-06-18T10:00:01Z"),
                        "a".repeat(64)
                ),
                com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
        );
        List<ScoredTransactionDocument> countProbe = IntStream
                .range(0, ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT + 1)
                .mapToObj(index -> document("probe-" + index))
                .toList();
        when(mongoTemplate.find(any(Query.class), eq(ScoredTransactionDocument.class)))
                .thenReturn(countProbe)
                .thenReturn(List.of(document));
        when(mapper.toDomain(document)).thenReturn(domain);

        var result = service.listScoredTransactions(
                PageRequest.of(0, 25),
                new ScoredTransactionSearchCriteria("customer-1", null, null)
        );

        assertThat(result.page().getContent()).containsExactly(domain);
        assertThat(result.page().getTotalElements()).isEqualTo(ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT);
        assertThat(result.totalElementsCapped()).isTrue();
        verify(mongoTemplate, never()).count(any(Query.class), eq(ScoredTransactionDocument.class));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, org.mockito.Mockito.times(2)).find(queryCaptor.capture(), eq(ScoredTransactionDocument.class));
        Query countQuery = queryCaptor.getAllValues().get(0);
        assertThat(countQuery.getLimit()).isEqualTo(ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT + 1);
        Query contentQuery = queryCaptor.getAllValues().get(1);
        assertThat(contentQuery.getSkip()).isZero();
        assertThat(contentQuery.getLimit()).isEqualTo(25);
    }

    @ParameterizedTest
    @CsvSource({
            "1,1000,1,1000",
            "25,399,25,9975",
            "25,400,0,10000",
            "25,401,0,10025",
            "51,195,51,9945",
            "51,196,4,9996",
            "51,197,0,10047",
            "100,99,100,9900",
            "100,100,0,10000"
    })
    void filteredPagesNeverReadBeyondTheTenThousandRowWindow(
            int size,
            int pageNumber,
            int expectedContentSize,
            long expectedOffset
    ) {
        List<ScoredTransactionDocument> countProbe = IntStream
                .range(0, ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT + 1)
                .mapToObj(index -> document("probe-" + index))
                .toList();
        List<ScoredTransactionDocument> content = IntStream.range(0, expectedContentSize)
                .mapToObj(index -> document("content-" + index))
                .toList();
        ScoredTransaction domain = domainTransaction();
        when(mongoTemplate.find(any(Query.class), eq(ScoredTransactionDocument.class)))
                .thenReturn(countProbe)
                .thenReturn(content);
        when(mapper.toDomain(any(ScoredTransactionDocument.class))).thenReturn(domain);

        var result = service.listScoredTransactions(
                PageRequest.of(pageNumber, size),
                new ScoredTransactionSearchCriteria("customer-1", null, null)
        );

        assertThat(result.page().getContent()).hasSize(expectedContentSize);
        assertThat(result.page().getTotalElements()).isEqualTo(10_000);
        assertThat(result.page().getTotalPages()).isEqualTo((int) Math.ceil(10_000d / size));
        assertThat(result.totalElementsCapped()).isTrue();
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, org.mockito.Mockito.times(expectedContentSize == 0 ? 1 : 2))
                .find(queryCaptor.capture(), eq(ScoredTransactionDocument.class));
        if (expectedContentSize > 0) {
            Query contentQuery = queryCaptor.getAllValues().get(1);
            assertThat(contentQuery.getSkip()).isEqualTo(expectedOffset);
            assertThat(contentQuery.getLimit()).isEqualTo(expectedContentSize);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "0,0,false",
            "9999,9999,false",
            "10000,10000,false",
            "10001,10000,true",
            "25000,10000,true"
    })
    void reportsExactAndCappedTotalsForBoundaryMatchCounts(int matches, long expectedTotal, boolean expectedCapped) {
        int boundedProbeSize = Math.min(matches, ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT + 1);
        List<ScoredTransactionDocument> probe = IntStream.range(0, boundedProbeSize)
                .mapToObj(index -> document("probe-" + index))
                .toList();
        when(mongoTemplate.find(any(Query.class), eq(ScoredTransactionDocument.class))).thenReturn(probe);

        var result = service.listScoredTransactions(
                PageRequest.of(400, 25), new ScoredTransactionSearchCriteria("merchant", null, null)
        );

        assertThat(result.page().getContent()).isEmpty();
        assertThat(result.page().getTotalElements()).isEqualTo(expectedTotal);
        assertThat(result.totalElementsCapped()).isEqualTo(expectedCapped);
        verify(mongoTemplate).find(any(Query.class), eq(ScoredTransactionDocument.class));
    }

    @Test
    void shouldSearchOnlyNormalizedIndexedIdentifierFields() {
        when(mongoTemplate.find(any(Query.class), eq(ScoredTransactionDocument.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());

        service.listScoredTransactions(
                PageRequest.of(0, 25),
                new ScoredTransactionSearchCriteria("Risk Merchant", null, null)
        );

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, org.mockito.Mockito.times(2)).find(queryCaptor.capture(), eq(ScoredTransactionDocument.class));
        String queryJson = queryCaptor.getAllValues().get(0).getQueryObject().toJson();

        assertThat(queryJson)
                .contains("transactionIdSearch", "customerIdSearch", "merchantIdSearch", "currencySearch")
                .contains("risk merchant")
                .doesNotContain("Risk Merchant", "merchantInfo.merchantName", "transactionAmount.currency", "$options", "\"i\"");
    }

    @Test
    void shouldUseTypedRiskAndClassificationCriteria() {
        when(mongoTemplate.find(any(Query.class), eq(ScoredTransactionDocument.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());

        service.listScoredTransactions(
                PageRequest.of(0, 25),
                new ScoredTransactionSearchCriteria(null, RiskLevel.CRITICAL, true)
        );

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, org.mockito.Mockito.times(2)).find(queryCaptor.capture(), eq(ScoredTransactionDocument.class));
        Document queryDocument = queryCaptor.getAllValues().get(0).getQueryObject();
        List<Document> filters = queryDocument.getList("$and", Document.class);

        assertThat(filters).anySatisfy(filter -> assertThat(filter).containsEntry("riskLevel", RiskLevel.CRITICAL));
        assertThat(filters).anySatisfy(filter -> assertThat(filter).containsEntry("alertRecommended", true));
    }

    private ScoredTransactionDocument document(String transactionId) {
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId(transactionId);
        return document;
    }

    private ScoredTransaction domainTransaction() {
        return new ScoredTransaction(
                "txn-1", "customer-1", null, null, null, null, null, null, null, null,
                List.of(), null, ScoringOccurrenceOwnership.authoritative(
                        "event-1", Instant.parse("2026-06-18T10:00:01Z"), "a".repeat(64)
                ), com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED
        );
    }
}
