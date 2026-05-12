package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TransactionMonitoringUseCase {

    void recordScoredTransaction(TransactionScoredEvent event);

    Page<ScoredTransaction> listScoredTransactions(Pageable pageable);

    default Page<ScoredTransaction> listScoredTransactions(Pageable pageable, ScoredTransactionSearchCriteria criteria) {
        return listScoredTransactions(pageable);
    }
}
