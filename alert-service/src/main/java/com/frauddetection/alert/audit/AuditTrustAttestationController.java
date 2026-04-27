package com.frauddetection.alert.audit;

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
    public AuditTrustAttestationResponse attest(
            @RequestParam(name = "source_service", required = false) String sourceService,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String mode
    ) {
        return attestationService.attest(sourceService, limit, mode);
    }
}
