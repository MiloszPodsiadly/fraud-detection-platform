package com.frauddetection.ingest.controller;

import com.frauddetection.common.events.source.SourceSignedInput;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.source.AttestedTransactionIngestService;
import com.frauddetection.ingest.source.SourceIngestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AttestedTransactionIngestController {
    private final AttestedTransactionIngestService service;
    public AttestedTransactionIngestController(AttestedTransactionIngestService service) { this.service = service; }
    @PostMapping(path = "/api/v1/transactions/attested", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestTransactionResponse ingest(@RequestHeader(name = "X-Source-Proof", required = false) String proof,
                                            HttpServletRequest request) {
        try {
            return service.ingest(proof, request.getInputStream().readNBytes(SourceSignedInput.MAX_PAYLOAD_BYTES + 1));
        } catch (java.io.IOException malformed) {
            throw new SourceIngestException(HttpStatus.BAD_REQUEST, "SOURCE_TRANSACTION_UNREADABLE");
        }
    }
}
