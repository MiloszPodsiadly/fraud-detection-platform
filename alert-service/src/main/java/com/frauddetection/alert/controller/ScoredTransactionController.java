package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.ScoredTransactionDetailResponse;
import com.frauddetection.alert.api.ScoredTransactionPageResponse;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceProjectionReadUnavailableException;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.ScoredTransactionSearchCriteria;
import com.frauddetection.alert.service.ScoredTransactionSearchPolicy;
import com.frauddetection.alert.service.ScoredTransactionSearchResult;
import com.frauddetection.alert.service.ScoredTransactionSearchValidationException;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/transactions")
public class ScoredTransactionController {

    private final TransactionMonitoringUseCase transactionMonitoringUseCase;
    private final ScoredTransactionResponseMapper responseMapper;
    private final EngineIntelligenceReadService engineIntelligenceReadService;
    private final EngineIntelligenceResponseMapper engineIntelligenceResponseMapper;
    private final ScoredTransactionSearchPolicy searchPolicy;
    private final AlertServiceMetrics metrics;

    public ScoredTransactionController(
            TransactionMonitoringUseCase transactionMonitoringUseCase,
            ScoredTransactionResponseMapper responseMapper,
            EngineIntelligenceReadService engineIntelligenceReadService,
            EngineIntelligenceResponseMapper engineIntelligenceResponseMapper,
            ScoredTransactionSearchPolicy searchPolicy,
            AlertServiceMetrics metrics
    ) {
        this.transactionMonitoringUseCase = transactionMonitoringUseCase;
        this.responseMapper = responseMapper;
        this.engineIntelligenceReadService = engineIntelligenceReadService;
        this.engineIntelligenceResponseMapper = engineIntelligenceResponseMapper;
        this.searchPolicy = searchPolicy;
        this.metrics = metrics;
    }

    @GetMapping("/scored")
    public ScoredTransactionPageResponse listScoredTransactions(
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
        var pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("scoredAt"),
                Sort.Order.desc("transactionId")
        ));
        var searchResult = runSearch(pageable, criteria, filterBucket);
        var result = searchResult.page();
        return new ScoredTransactionPageResponse(
                result.getContent().stream().map(responseMapper::toResponse).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize(),
                searchResult.totalElementsCapped()
        );
    }

    @GetMapping("/scored/{transactionId}")
    public ScoredTransactionDetailResponse getScoredTransaction(@PathVariable String transactionId) {
        var scoredTransaction = transactionMonitoringUseCase.getScoredTransaction(transactionId);
        var engineIntelligence = engineIntelligenceResponse(scoredTransaction);
        return responseMapper.toDetailResponse(scoredTransaction, engineIntelligence);
    }

    private com.frauddetection.alert.api.EngineIntelligenceResponse engineIntelligenceResponse(
            com.frauddetection.alert.domain.ScoredTransaction scoredTransaction
    ) {
        try {
            return engineIntelligenceResponseMapper.toResponse(engineIntelligenceReadService.readForOccurrence(
                    scoredTransaction.transactionId(),
                    scoredTransaction.scoringOccurrenceOwnership()
            ));
        } catch (EngineIntelligenceProjectionReadUnavailableException exception) {
            return engineIntelligenceResponseMapper.unavailable();
        }
    }

    private ScoredTransactionSearchResult runSearch(
            org.springframework.data.domain.Pageable pageable,
            ScoredTransactionSearchCriteria criteria,
            String filterBucket
    ) {
        try {
            var result = criteria.hasFilters()
                    ? transactionMonitoringUseCase.listScoredTransactions(pageable, criteria)
                    : new ScoredTransactionSearchResult(
                            transactionMonitoringUseCase.listScoredTransactions(pageable),
                            false
                    );
            metrics.recordScoredTransactionSearchRequest("success", filterBucket);
            metrics.recordScoredTransactionSearchPageSize(pageable.getPageSize());
            return result;
        } catch (RuntimeException exception) {
            metrics.recordScoredTransactionSearchRequest("failure", filterBucket);
            throw exception;
        }
    }
}
