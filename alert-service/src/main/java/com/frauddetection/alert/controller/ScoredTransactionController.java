package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.api.ScoredTransactionResponse;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size
    ) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "scoredAt"));
        var result = transactionMonitoringUseCase.listScoredTransactions(pageable);
        return new PagedResponse<>(
                result.getContent().stream().map(responseMapper::toResponse).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }
}
