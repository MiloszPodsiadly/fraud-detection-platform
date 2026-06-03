package com.frauddetection.alert.engineintelligence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions/scored")
public class EngineIntelligenceFeedbackController {

    private final EngineIntelligenceFeedbackService service;

    public EngineIntelligenceFeedbackController(EngineIntelligenceFeedbackService service) {
        this.service = service;
    }

    @PostMapping("/{transactionId}/engine-intelligence/feedback")
    public ResponseEntity<EngineIntelligenceFeedbackResponse> submit(
            @PathVariable String transactionId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody EngineIntelligenceFeedbackRequest request
    ) {
        EngineIntelligenceFeedbackResponse response = service.submit(transactionId, request, idempotencyKey);
        HttpStatus status = "EXISTING".equals(response.operationStatus()) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
