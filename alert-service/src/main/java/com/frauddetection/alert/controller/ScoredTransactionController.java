package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.ScoredTransactionResponse;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ScoredTransactionSearchCriteria;
import com.frauddetection.alert.service.ScoredTransactionSearchPolicy;
import com.frauddetection.alert.service.ScoredTransactionSearchValidationException;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/transactions")
public class ScoredTransactionController {

    private final TransactionMonitoringUseCase transactionMonitoringUseCase;
    private final ScoredTransactionResponseMapper responseMapper;
    private final ScoredTransactionSearchPolicy searchPolicy;
    private final AlertServiceMetrics metrics;

    public ScoredTransactionController(
            TransactionMonitoringUseCase transactionMonitoringUseCase,
            ScoredTransactionResponseMapper responseMapper,
            ScoredTransactionSearchPolicy searchPolicy,
            AlertServiceMetrics metrics
    ) {
        this.transactionMonitoringUseCase = transactionMonitoringUseCase;
        this.responseMapper = responseMapper;
        this.searchPolicy = searchPolicy;
        this.metrics = metrics;
    }

    @GetMapping("/scored")
    public PagedResponse<ScoredTransactionResponse> listScoredTransactions(
            @RequestParam MultiValueMap<String, String> rawParams
    ) {
        ScoredTransactionSearchCriteria criteria;
        int page;
        int size;
        try {
            searchPolicy.validateParameters(rawParams);
            page = searchPolicy.page(rawParams);
            size = searchPolicy.size(rawParams);
            searchPolicy.validatePageAndSize(page, size);
            criteria = searchPolicy.criteria(
                    searchPolicy.value(rawParams, "query"),
                    searchPolicy.value(rawParams, "riskLevel"),
                    searchPolicy.value(rawParams, "classification")
            );
        } catch (ScoredTransactionSearchValidationException exception) {
            metrics.recordScoredTransactionSearchRequest("rejected", searchPolicy.filterBucket(rawParams));
            throw exception;
        }
        String filterBucket = searchPolicy.filterBucket(criteria);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "scoredAt"));
        var result = runSearch(pageable, criteria, filterBucket);
        return new PagedResponse<>(
                result.getContent().stream().map(responseMapper::toResponse).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    private org.springframework.data.domain.Page<com.frauddetection.alert.domain.ScoredTransaction> runSearch(
            org.springframework.data.domain.Pageable pageable,
            ScoredTransactionSearchCriteria criteria,
            String filterBucket
    ) {
        try {
            var result = criteria.hasFilters()
                    ? transactionMonitoringUseCase.listScoredTransactions(pageable, criteria)
                    : transactionMonitoringUseCase.listScoredTransactions(pageable);
            metrics.recordScoredTransactionSearchRequest("success", filterBucket);
            metrics.recordScoredTransactionSearchPageSize(pageable.getPageSize());
            return result;
        } catch (RuntimeException exception) {
            metrics.recordScoredTransactionSearchRequest("failure", filterBucket);
            throw exception;
        }
    }
}
