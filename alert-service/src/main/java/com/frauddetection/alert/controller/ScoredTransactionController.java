package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.ScoredTransactionResponse;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.service.ScoredTransactionSearchCriteria;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    public ScoredTransactionController(
            TransactionMonitoringUseCase transactionMonitoringUseCase,
            ScoredTransactionResponseMapper responseMapper
    ) {
        this.transactionMonitoringUseCase = transactionMonitoringUseCase;
        this.responseMapper = responseMapper;
    }

    @GetMapping("/scored")
    public PagedResponse<ScoredTransactionResponse> listScoredTransactions(
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = ScoredTransactionSearchCriteria.MAX_QUERY_LENGTH) String query,
            @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|LOW|MEDIUM|HIGH|CRITICAL") String riskLevel,
            @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|LEGITIMATE|SUSPICIOUS") String classification
    ) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "scoredAt"));
        var criteria = new ScoredTransactionSearchCriteria(query, riskLevel, classification);
        var result = criteria.hasFilters()
                ? transactionMonitoringUseCase.listScoredTransactions(pageable, criteria)
                : transactionMonitoringUseCase.listScoredTransactions(pageable);
        return new PagedResponse<>(
                result.getContent().stream().map(responseMapper::toResponse).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }
}
