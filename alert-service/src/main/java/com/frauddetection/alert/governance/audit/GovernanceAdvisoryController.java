package com.frauddetection.alert.governance.audit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/governance/advisories")
public class GovernanceAdvisoryController {

    private final GovernanceAdvisoryProjectionService projectionService;

    public GovernanceAdvisoryController(GovernanceAdvisoryProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @GetMapping
    public GovernanceAdvisoryListResponse listAdvisories(
            @RequestParam(required = false)
            @Pattern(regexp = "^(LOW|MEDIUM|HIGH|CRITICAL)$")
            String severity,
            @RequestParam(name = "model_version", required = false)
            @Pattern(regexp = "^[A-Za-z0-9._-]{1,80}$")
            String modelVersion,
            @RequestParam(required = false, defaultValue = "100")
            @Min(1)
            @Max(100)
            Integer limit,
            @RequestParam(name = "lifecycle_status", required = false)
            GovernanceAdvisoryLifecycleStatus lifecycleStatus
    ) {
        return projectionService.listAdvisories(new GovernanceAdvisoryQuery(severity, modelVersion, limit), lifecycleStatus);
    }

    @GetMapping("/{eventId}")
    public GovernanceAdvisoryEvent getAdvisory(
            @PathVariable
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,120}$")
            String eventId
    ) {
        return projectionService.getAdvisory(eventId);
    }
}
