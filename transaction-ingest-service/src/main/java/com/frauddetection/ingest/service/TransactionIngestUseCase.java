package com.frauddetection.ingest.service;

import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.IngestTransactionResponse;

public interface TransactionIngestUseCase {

    IngestTransactionResponse ingest(IngestTransactionRequest request);
}
