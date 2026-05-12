package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

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
    private final TransactionMonitoringService service = new TransactionMonitoringService(repository, mapper, mongoTemplate);

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
                List.of()
        );
        List<ScoredTransactionDocument> countProbe = IntStream
                .range(0, ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT + 1)
                .mapToObj(index -> document("probe-" + index))
                .toList();
        when(mongoTemplate.find(any(Query.class), eq(ScoredTransactionDocument.class)))
                .thenReturn(List.of(document))
                .thenReturn(countProbe);
        when(mapper.toDomain(document)).thenReturn(domain);

        var result = service.listScoredTransactions(
                PageRequest.of(0, 25),
                new ScoredTransactionSearchCriteria("customer-1", "ALL", "ALL")
        );

        assertThat(result.getContent()).containsExactly(domain);
        assertThat(result.getTotalElements()).isEqualTo(ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT);
        verify(mongoTemplate, never()).count(any(Query.class), eq(ScoredTransactionDocument.class));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, org.mockito.Mockito.times(2)).find(queryCaptor.capture(), eq(ScoredTransactionDocument.class));
        Query countQuery = queryCaptor.getAllValues().get(1);
        assertThat(countQuery.getLimit()).isEqualTo(ScoredTransactionSearchPolicy.MAX_FILTERED_TOTAL_COUNT + 1);
    }

    @Test
    void shouldSearchOnlyAllowlistedIdentifierFields() {
        when(mongoTemplate.find(any(Query.class), eq(ScoredTransactionDocument.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());

        service.listScoredTransactions(
                PageRequest.of(0, 25),
                new ScoredTransactionSearchCriteria("risk merchant", "ALL", "ALL")
        );

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, org.mockito.Mockito.times(2)).find(queryCaptor.capture(), eq(ScoredTransactionDocument.class));
        String queryJson = queryCaptor.getAllValues().get(0).getQueryObject().toJson();

        assertThat(queryJson)
                .contains("transactionId", "customerId", "merchantInfo.merchantId", "transactionAmount.currency")
                .doesNotContain("merchantInfo.merchantName");
    }

    private ScoredTransactionDocument document(String transactionId) {
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId(transactionId);
        return document;
    }
}
