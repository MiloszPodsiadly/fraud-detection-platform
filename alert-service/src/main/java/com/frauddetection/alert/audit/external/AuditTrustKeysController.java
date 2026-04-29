package com.frauddetection.alert.audit.external;

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
    public List<AuditTrustAuthorityKey> keys() {
        return trustAuthorityClient.keys();
    }
}
