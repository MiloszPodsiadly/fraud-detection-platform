package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit/trust/keys")
public class AuditTrustKeysController {

    private final AuditTrustAuthorityClient trustAuthorityClient;

    public AuditTrustKeysController(AuditTrustAuthorityClient trustAuthorityClient) {
        this.trustAuthorityClient = trustAuthorityClient;
    }

    @GetMapping
    @AuditedSensitiveRead(category = ReadAccessEndpointCategory.AUDIT_TRUST_KEY_REGISTRY,
            resourceType = ReadAccessResourceType.AUDIT_TRUST_KEY_REGISTRY)
    public List<AuditTrustAuthorityKey> keys() {
        return trustAuthorityClient.keys();
    }
}
