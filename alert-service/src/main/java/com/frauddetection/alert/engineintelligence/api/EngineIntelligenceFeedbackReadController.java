package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/transactions/scored")
public class EngineIntelligenceFeedbackReadController {

    private final EngineIntelligenceFeedbackReadService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;
    private final EngineIntelligenceFeedbackReadQueryPolicy queryPolicy;

    public EngineIntelligenceFeedbackReadController(
            EngineIntelligenceFeedbackReadService service,
            SensitiveReadAuditService sensitiveReadAuditService,
            EngineIntelligenceFeedbackReadQueryPolicy queryPolicy
    ) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.sensitiveReadAuditService = Objects.requireNonNull(sensitiveReadAuditService, "sensitiveReadAuditService is required");
        this.queryPolicy = Objects.requireNonNull(queryPolicy, "queryPolicy is required");
    }

    @GetMapping("/{transactionId}/engine-intelligence/feedback")
    @AuditedSensitiveRead
    public EngineIntelligenceFeedbackReadModel read(
            @PathVariable String transactionId,
            @RequestParam MultiValueMap<String, String> rawParams,
            HttpServletRequest request
    ) {
        int limit = queryPolicy.limit(rawParams);
        EngineIntelligenceFeedbackReadModel response = service.read(transactionId, limit);
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.ENGINE_INTELLIGENCE_FEEDBACK_READ,
                ReadAccessResourceType.ENGINE_INTELLIGENCE_FEEDBACK,
                response.transactionId(),
                response.feedback().size(),
                request
        );
        return response;
    }
}
