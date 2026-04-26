package com.frauddetection.alert.governance.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/governance/advisories/{eventId}/audit")
public class GovernanceAuditController {

    private final GovernanceAuditService governanceAuditService;

    public GovernanceAuditController(GovernanceAuditService governanceAuditService) {
        this.governanceAuditService = governanceAuditService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GovernanceAuditEventResponse recordAudit(
            @PathVariable
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,120}$")
            String eventId,
            @Valid @RequestBody GovernanceAuditRequest request
    ) {
        return governanceAuditService.appendAudit(eventId, request);
    }

    @GetMapping
    public GovernanceAuditHistoryResponse getAuditHistory(
            @PathVariable
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9._:-]{1,120}$")
            String eventId
    ) {
        return governanceAuditService.history(eventId);
    }
}
