package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/transactions/scored")
public class EngineIntelligenceFeedbackReadController {

    private final EngineIntelligenceFeedbackReadService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public EngineIntelligenceFeedbackReadController(
            EngineIntelligenceFeedbackReadService service,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.sensitiveReadAuditService = Objects.requireNonNull(sensitiveReadAuditService, "sensitiveReadAuditService is required");
    }

    @GetMapping("/{transactionId}/engine-intelligence/feedback")
    @AuditedSensitiveRead
    public EngineIntelligenceFeedbackReadModel read(
            @PathVariable String transactionId,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
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
