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
    private final SuspiciousTransactionReadService service = new SuspiciousTransactionReadService(repository, mongoTemplate);

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
    void searchUsesBoundedPageableAndMapsPageContent() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("status", "NEW");
        params.add("riskLevel", "HIGH");
        params.add("customerId", "customer-1");
        params.add("detectedFrom", "2026-05-18T00:00:00Z");
        params.add("sort", "riskScore,desc");
        params.add("size", "20");
        SuspiciousTransactionSearchQuery query = SuspiciousTransactionSearchQuery.from(params);
        when(mongoTemplate.count(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of(document("suspicious-1")));

        var page = service.search(query);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SuspiciousTransactionDocument.class));
        assertThat(queryCaptor.getValue().getLimit()).isEqualTo(20);
        assertThat(queryCaptor.getValue().getSortObject().toJson()).contains("riskScore");
        verify(repository, never()).findAll();
    }

    @Test
    void emptySearchReturnsEmptyPage() {
        when(mongoTemplate.count(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(SuspiciousTransactionDocument.class))).thenReturn(List.of());

        var page = service.search(SuspiciousTransactionSearchQuery.from(new LinkedMultiValueMap<>()));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
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
