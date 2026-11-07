package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TransactionMonitoringUseCase {

    ScoringOccurrenceAdmissionResult recordScoredTransaction(TransactionScoredEvent event);

    Page<ScoredTransaction> listScoredTransactions(Pageable pageable);

    ScoredTransaction getScoredTransaction(String transactionId);

    default ScoredTransactionSearchResult listScoredTransactions(Pageable pageable, ScoredTransactionSearchCriteria criteria) {
        return new ScoredTransactionSearchResult(listScoredTransactions(pageable), false);
    }
}
