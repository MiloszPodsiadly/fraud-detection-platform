package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoredTransaction;
import org.springframework.data.domain.Page;

public record ScoredTransactionSearchResult(Page<ScoredTransaction> page, boolean totalElementsCapped) {
}
