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
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody TrustSignRequest request
    ) {
        return service.sign(TrustAuthorityRequestCredentials.of(serviceName, environment, instanceId, requestId, signedAt, signature, authorization), request);
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
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody TrustVerifyRequest request
    ) {
        return service.verify(TrustAuthorityRequestCredentials.of(serviceName, environment, instanceId, requestId, signedAt, signature, authorization), request);
    }

    @GetMapping("/audit/integrity")
    public TrustAuthorityAuditIntegrityResponse auditIntegrity(
            @RequestHeader(name = "X-Internal-Service-Name", required = false) String serviceName,
            @RequestHeader(name = "X-Internal-Service-Environment", required = false) String environment,
            @RequestHeader(name = "X-Internal-Service-Instance-Id", required = false) String instanceId,
            @RequestHeader(name = "X-Internal-Trust-Request-Id", required = false) String requestId,
            @RequestHeader(name = "X-Internal-Trust-Signed-At", required = false) String signedAt,
            @RequestHeader(name = "X-Internal-Trust-Signature", required = false) String signature,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @org.springframework.web.bind.annotation.RequestParam(name = "mode", required = false, defaultValue = "WINDOW") String mode,
            @org.springframework.web.bind.annotation.RequestParam(name = "limit", required = false, defaultValue = "10000") int limit
    ) {
        return service.auditIntegrity(TrustAuthorityRequestCredentials.of(serviceName, environment, instanceId, requestId, signedAt, signature, authorization), limit, mode);
    }

    @GetMapping("/audit/head")
    public TrustAuthorityAuditHeadResponse auditHead(
            @RequestHeader(name = "X-Internal-Service-Name", required = false) String serviceName,
            @RequestHeader(name = "X-Internal-Service-Environment", required = false) String environment,
            @RequestHeader(name = "X-Internal-Service-Instance-Id", required = false) String instanceId,
            @RequestHeader(name = "X-Internal-Trust-Request-Id", required = false) String requestId,
            @RequestHeader(name = "X-Internal-Trust-Signed-At", required = false) String signedAt,
            @RequestHeader(name = "X-Internal-Trust-Signature", required = false) String signature,
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        return service.auditHead(TrustAuthorityRequestCredentials.of(serviceName, environment, instanceId, requestId, signedAt, signature, authorization));
    }
}
