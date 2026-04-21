package com.frauddetection.ingest.controller;

import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.service.TransactionIngestUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionIngestController {

    private final TransactionIngestUseCase transactionIngestUseCase;

    public TransactionIngestController(TransactionIngestUseCase transactionIngestUseCase) {
        this.transactionIngestUseCase = transactionIngestUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestTransactionResponse ingestTransaction(@Valid @RequestBody IngestTransactionRequest request) {
        return transactionIngestUseCase.ingest(request);
    }
}
