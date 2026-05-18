package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.suspicious.DetectionSource;
import com.frauddetection.alert.suspicious.SuspiciousTransactionDocument;
import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import com.frauddetection.alert.suspicious.SuspiciousTransactionStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspiciousTransactionReadServiceTest {

    private final SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final SuspiciousTransactionCursorCodec cursorCodec = new SuspiciousTransactionCursorCodec();
    private final SuspiciousTransactionReadService service =
            new SuspiciousTransactionReadService(repository, mongoTemplate, cursorCodec);

    @Test
    void findByIdReturnsMappedResponseAndDoesNotQueryTransactionId() {
        SuspiciousTransactionDocument document = document("suspicious-1");
        when(repository.findById("suspicious-1")).thenReturn(Optional.of(document));

        Optional<SuspiciousTransactionResponse> response = service.findById("suspicious-1");

        assertThat(response).isPresent();
        assertThat(response.get().suspiciousTransactionId()).isEqualTo("suspicious-1");
        assertThat(response.get().evidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        verify(repository, never()).findByTransactionIdAndSourceEventId(any(), any());
    }

    @Test
    void findByIdMissingReturnsEmpty() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.findById("missing")).isEmpty();
    }

    @Test
    void searchUsesBoundedKeysetQueryAndMapsContent() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("status", "NEW");
        params.add("riskLevel", "HIGH");
        params.add("customerId", "customer-1");
        params.add("detectedFrom", "2026-05-18T00:00:00Z");
        params.add("size", "20");
        SuspiciousTransactionSearchQuery query = SuspiciousTransactionSearchQuery.from(params);
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of(document("suspicious-1")));

        SuspiciousTransactionSliceResponse slice = service.search(query);

        assertThat(slice.content()).hasSize(1);
        assertThat(slice.content().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getLimit()).isEqualTo(21);
        assertThat(queryCaptor.getValue().getSortObject().toJson())
                .contains("detectedAt")
                .contains("suspiciousTransactionId");
        verify(mongoTemplate, never()).count(any(Query.class), eq(SuspiciousTransactionDocument.class));
        verify(repository, never()).findAll();
    }

    @Test
    void searchDoesNotExecuteCountForEmptyFilters() {
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        service.search(SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>()));

        verify(mongoTemplate, never()).count(any(Query.class), eq(SuspiciousTransactionDocument.class));
        verify(mongoTemplate).find(any(Query.class), eq(SuspiciousTransactionDocument.class));
    }

    @Test
    void searchFetchesAtMostSizePlusOne() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("size", "20");
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        service.search(SuspiciousTransactionSearchQuery.from(params));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getLimit()).isEqualTo(21);
    }

    @Test
    void searchDoesNotUseSkip() {
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        service.search(SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>()));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getSkip()).isZero();
    }

    @Test
    void firstPageUsesFixedSort() {
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        service.search(SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>()));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getSortObject().toJson())
                .contains("\"detectedAt\": -1")
                .contains("\"suspiciousTransactionId\": -1");
    }

    @Test
    void nextPageUsesCursorCriteria() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("cursor", cursorCodec.encode(Instant.parse("2026-05-18T10:00:00Z"), "suspicious-2"));
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        service.search(SuspiciousTransactionSearchQuery.from(params));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getQueryObject().toString())
                .contains("$or")
                .contains("detectedAt")
                .contains("suspiciousTransactionId")
                .contains("suspicious-2");
    }

    @Test
    void filteredNextPageCombinesFiltersAndCursorInSingleBoundedQuery() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("status", "NEW");
        params.add("riskLevel", "HIGH");
        params.add("cursor", cursorCodec.encode(Instant.parse("2026-05-18T10:00:00Z"), "suspicious-2"));
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        service.search(SuspiciousTransactionSearchQuery.from(params));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getQueryObject().toString())
                .contains("$and")
                .contains("$or")
                .contains("status")
                .contains("riskLevel")
                .contains("suspicious-2");
    }

    @Test
    void searchWithExtraItemReturnsHasNextTrue() {
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class)))
                .thenReturn(documents(21));

        SuspiciousTransactionSliceResponse response = service.search(SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>()));

        assertThat(response.content()).hasSize(20);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
    }

    @Test
    void searchWithLessThanSizeReturnsHasNextFalse() {
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class)))
                .thenReturn(documents(5));

        SuspiciousTransactionSliceResponse response = service.search(SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>()));

        assertThat(response.content()).hasSize(5);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void searchWithExactlySizeReturnsHasNextFalse() {
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class)))
                .thenReturn(documents(20));

        SuspiciousTransactionSliceResponse response = service.search(SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>()));

        assertThat(response.content()).hasSize(20);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void emptySearchReturnsEmptyCursorSlice() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("size", "20");
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        SuspiciousTransactionSliceResponse response = service.search(SuspiciousTransactionSearchQuery.from(params));

        assertThat(response.content()).isEmpty();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void nextCursorGeneratedFromLastReturnedItem() {
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class)))
                .thenReturn(documents(3));
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("size", "2");

        SuspiciousTransactionSliceResponse response = service.search(SuspiciousTransactionSearchQuery.from(params));

        assertThat(response.content()).hasSize(2);
        SuspiciousTransactionCursor cursor = cursorCodec.decode(response.nextCursor());
        assertThat(cursor.suspiciousTransactionId()).isEqualTo("suspicious-2");
    }

    @Test
    void emptySearchUsesCursorSliceNoCountNoSkip() {
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        service.search(SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>()));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, never()).count(any(Query.class), eq(SuspiciousTransactionDocument.class));
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getSkip()).isZero();
        assertThat(queryCaptor.getValue().getLimit()).isEqualTo(SuspiciousTransactionSearchQuery.DEFAULT_SIZE + 1);
    }

    @Test
    void searchResponseDoesNotExposeTotalElementsOrTotalPages() {
        assertThat(java.util.Arrays.stream(SuspiciousTransactionSliceResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList()).doesNotContain("page", "totalElements", "totalPages", "totalCount", "offset");
    }

    private static List<SuspiciousTransactionDocument> documents(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> document("suspicious-" + index))
                .toList();
    }

    static SuspiciousTransactionDocument document(String id) {
        SuspiciousTransactionDocument document = new SuspiciousTransactionDocument();
        document.setSuspiciousTransactionId(id);
        document.setTransactionId("txn-1");
        document.setSourceEventId("event-1");
        document.setCorrelationId("corr-1");
        document.setCustomerId("customer-1");
        document.setAccountId("account-1");
        document.setRiskScore(0.91);
        document.setRiskLevel(RiskLevel.HIGH);
        document.setDetectionSource(DetectionSource.RULE_ENGINE);
        document.setReasonCodes(List.of("HIGH_AMOUNT"));
        document.setEvidenceStatus(EvidenceStatus.PARTIAL);
        document.setEvidenceSnapshotItemCount(1);
        document.setEvidenceProjectionState("PARTIAL_METADATA");
        document.setStatus(SuspiciousTransactionStatus.NEW);
        document.setDetectedAt(Instant.parse("2026-05-18T10:00:00Z"));
        document.setCreatedAt(Instant.parse("2026-05-18T10:01:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-18T10:02:00Z"));
        document.setScoreDecisionId("decision-1");
        document.setScoringStrategy("RULE_BASED");
        document.setModelName("model-a");
        document.setModelVersion("v1");
        return document;
    }
}
