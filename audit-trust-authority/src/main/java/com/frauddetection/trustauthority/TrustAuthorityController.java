package com.frauddetection.trustauthority;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trust")
public class TrustAuthorityController {

    private final TrustAuthorityService service;

    public TrustAuthorityController(TrustAuthorityService service) {
        this.service = service;
    }

    @PostMapping("/sign")
    public TrustSignResponse sign(
            @RequestHeader(name = "X-Internal-Service-Name", required = false) String serviceName,
            @RequestHeader(name = "X-Internal-Service-Environment", required = false) String environment,
            @RequestHeader(name = "X-Internal-Service-Instance-Id", required = false) String instanceId,
            @RequestHeader(name = "X-Internal-Trust-Request-Id", required = false) String requestId,
            @RequestHeader(name = "X-Internal-Trust-Signed-At", required = false) String signedAt,
            @RequestHeader(name = "X-Internal-Trust-Signature", required = false) String signature,
            @Valid @RequestBody TrustSignRequest request
    ) {
        return service.sign(TrustAuthorityRequestCredentials.of(serviceName, environment, instanceId, requestId, signedAt, signature), request);
    }

    @GetMapping("/keys")
    public List<TrustKeyResponse> keys() {
        return service.keys();
    }

    @PostMapping("/verify")
    public TrustVerifyResponse verify(
            @RequestHeader(name = "X-Internal-Service-Name", required = false) String serviceName,
            @RequestHeader(name = "X-Internal-Service-Environment", required = false) String environment,
            @RequestHeader(name = "X-Internal-Service-Instance-Id", required = false) String instanceId,
            @RequestHeader(name = "X-Internal-Trust-Request-Id", required = false) String requestId,
            @RequestHeader(name = "X-Internal-Trust-Signed-At", required = false) String signedAt,
            @RequestHeader(name = "X-Internal-Trust-Signature", required = false) String signature,
            @Valid @RequestBody TrustVerifyRequest request
    ) {
        return service.verify(TrustAuthorityRequestCredentials.of(serviceName, environment, instanceId, requestId, signedAt, signature), request);
    }

    @GetMapping("/audit/integrity")
    public TrustAuthorityAuditIntegrityResponse auditIntegrity(
            @RequestHeader(name = "X-Internal-Service-Name", required = false) String serviceName,
            @RequestHeader(name = "X-Internal-Service-Environment", required = false) String environment,
            @RequestHeader(name = "X-Internal-Service-Instance-Id", required = false) String instanceId,
            @RequestHeader(name = "X-Internal-Trust-Request-Id", required = false) String requestId,
            @RequestHeader(name = "X-Internal-Trust-Signed-At", required = false) String signedAt,
            @RequestHeader(name = "X-Internal-Trust-Signature", required = false) String signature
    ) {
        return service.auditIntegrity(TrustAuthorityRequestCredentials.of(serviceName, environment, instanceId, requestId, signedAt, signature), 10_000);
    }

    @GetMapping("/audit/head")
    public TrustAuthorityAuditHeadResponse auditHead(
            @RequestHeader(name = "X-Internal-Service-Name", required = false) String serviceName,
            @RequestHeader(name = "X-Internal-Service-Environment", required = false) String environment,
            @RequestHeader(name = "X-Internal-Service-Instance-Id", required = false) String instanceId,
            @RequestHeader(name = "X-Internal-Trust-Request-Id", required = false) String requestId,
            @RequestHeader(name = "X-Internal-Trust-Signed-At", required = false) String signedAt,
            @RequestHeader(name = "X-Internal-Trust-Signature", required = false) String signature
    ) {
        return service.auditHead(TrustAuthorityRequestCredentials.of(serviceName, environment, instanceId, requestId, signedAt, signature));
    }
}
