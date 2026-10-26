package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP adapter; the service checks scope and reconstructs publication state. */
@RestController
@RequestMapping("/api/v1/governance/model-policies")
public class PolicyPublicationStatusController {
    private final PolicyPublicationStatusService service;

    public PolicyPublicationStatusController(PolicyPublicationStatusService service) {
        this.service = service;
    }

    @GetMapping("/{id}/publication")
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.MODEL_POLICY_PUBLICATION_STATUS,
            resourceType = ReadAccessResourceType.MODEL_POLICY_PUBLICATION, resourceIdVariable = "id")
    public PolicyPublicationStatusService.Status publication(Authentication authentication,
            @PathVariable String id) {
        return service.publication(PolicyGovernanceActor.from(authentication), id);
    }
}
