package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class TransactionMonitoringService implements TransactionMonitoringUseCase {

    private final ScoredTransactionRepository repository;
    private final ScoredTransactionDocumentMapper mapper;

    public TransactionMonitoringService(ScoredTransactionRepository repository, ScoredTransactionDocumentMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public void recordScoredTransaction(TransactionScoredEvent event) {
        repository.save(mapper.toDocument(event));
    }

    @Override
    public Page<ScoredTransaction> listScoredTransactions(Pageable pageable) {
        return repository.findAll(pageable)
                .map(mapper::toDomain);
    }
}
