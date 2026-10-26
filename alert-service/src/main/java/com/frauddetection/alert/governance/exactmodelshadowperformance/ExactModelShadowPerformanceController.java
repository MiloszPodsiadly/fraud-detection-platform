package com.frauddetection.alert.governance.exactmodelshadowperformance;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/governance/model-shadow-performance")
public class ExactModelShadowPerformanceController {

    private final ExactModelShadowPerformanceReadService readService;

    public ExactModelShadowPerformanceController(ExactModelShadowPerformanceReadService readService) {
        this.readService = readService;
    }

    @GetMapping
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.EXACT_MODEL_SHADOW_PERFORMANCE,
            resourceType = ReadAccessResourceType.EXACT_MODEL_SHADOW_PERFORMANCE,
            resourceIdParameter = "modelArtifactSha256")
    public JsonNode find(
            @RequestParam String modelName,
            @RequestParam String modelVersion,
            @RequestParam String featureContractVersion,
            @RequestParam String modelArtifactSha256,
            @RequestParam(required = false) String fromInclusive,
            @RequestParam(required = false) String toInclusive
    ) {
        try {
            return readService.find(new ExactModelShadowPerformanceSelector(
                    modelName, modelVersion, featureContractVersion, modelArtifactSha256,
                    fromInclusive, toInclusive));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }
}
