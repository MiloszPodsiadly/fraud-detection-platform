package com.frauddetection.alert.audit;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/trust/attestation")
public class AuditTrustAttestationController {

    private final AuditTrustAttestationService attestationService;

    public AuditTrustAttestationController(AuditTrustAttestationService attestationService) {
        this.attestationService = attestationService;
    }

    @GetMapping
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.AUDIT_TRUST_ATTESTATION,
            resourceType = ReadAccessResourceType.AUDIT_TRUST_ATTESTATION)
    public AuditTrustAttestationResponse attest(
            @RequestParam(name = "source_service", required = false) String sourceService,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String mode
    ) {
        return attestationService.attest(sourceService, limit, mode);
    }
}
