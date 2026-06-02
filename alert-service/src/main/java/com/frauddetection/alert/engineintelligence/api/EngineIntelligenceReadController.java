package com.frauddetection.alert.engineintelligence.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions/scored")
public class EngineIntelligenceReadController {

    private final EngineIntelligenceReadService service;

    public EngineIntelligenceReadController(EngineIntelligenceReadService service) {
        this.service = service;
    }

    @GetMapping("/{transactionId}/engine-intelligence")
    public EngineIntelligenceReadModel read(@PathVariable String transactionId) {
        return service.read(transactionId);
    }
}
