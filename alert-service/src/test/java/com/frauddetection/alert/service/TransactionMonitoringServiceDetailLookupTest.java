package com.frauddetection.alert.service;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionMonitoringServiceDetailLookupTest {

    private final ScoredTransactionRepository repository = mock(ScoredTransactionRepository.class);
    private final ScoredTransactionDocumentMapper mapper = mock(ScoredTransactionDocumentMapper.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final TransactionMonitoringService service = new TransactionMonitoringService(
            repository,
            mapper,
            mongoTemplate,
            new ScoredTransactionSearchPolicy(),
            mock(EngineIntelligenceProjectionService.class)
    );

    @Test
    void findsScoredTransactionByExactTransactionId() {
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        document.setTransactionId("txn-1");
        var domain = new com.frauddetection.alert.domain.ScoredTransaction(
                "txn-1",
                "customer-1",
                null,
                null,
                null,
                null,
                null,
                0.75d,
                RiskLevel.HIGH,
                true,
                List.of("RULE_MATCH")
        );
        when(repository.findByTransactionId("txn-1")).thenReturn(Optional.of(document));
        when(mapper.toDomain(document)).thenReturn(domain);

        assertThat(service.getScoredTransaction(" txn-1 ")).isSameAs(domain);

        verify(repository).findByTransactionId("txn-1");
        verify(repository, never()).findAll();
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void returnsNotFoundForUnknownTransactionId() {
        when(repository.findByTransactionId("txn-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getScoredTransaction("txn-missing"))
                .isInstanceOf(ScoredTransactionNotFoundException.class)
                .hasMessage("Scored transaction not found.");

        verify(repository).findByTransactionId("txn-missing");
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void rejectsInvalidTransactionIdBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.getScoredTransaction("txn/../secret"))
                .isInstanceOf(ScoredTransactionReadValidationException.class)
                .hasMessage("Invalid scored transaction read request.");

        verifyNoInteractions(repository, mapper, mongoTemplate);
    }
}
