package com.frauddetection.alert.feedback;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions/scored/{transactionId}/feedback")
public class FraudFeedbackController {

    private final FraudFeedbackService service;

    public FraudFeedbackController(FraudFeedbackService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FraudFeedbackResponse create(
            @PathVariable String transactionId,
            @RequestBody CreateFraudFeedbackRequest request
    ) {
        return service.create(transactionId, request);
    }

    @GetMapping
    public FraudFeedbackResponse get(@PathVariable String transactionId) {
        return service.get(transactionId);
    }
}
