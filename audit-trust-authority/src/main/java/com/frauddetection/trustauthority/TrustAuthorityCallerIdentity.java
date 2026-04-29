package com.frauddetection.trustauthority;

import org.springframework.util.StringUtils;

public record TrustAuthorityCallerIdentity(
        String serviceName,
        String environment,
        String instanceId
) {
    static TrustAuthorityCallerIdentity of(String serviceName, String environment, String instanceId) {
        return new TrustAuthorityCallerIdentity(
                StringUtils.hasText(serviceName) ? serviceName.trim() : "unknown",
                StringUtils.hasText(environment) ? environment.trim() : "unknown",
                StringUtils.hasText(instanceId) ? instanceId.trim() : null
        );
    }

    String auditIdentity() {
        String base = serviceName + "@" + environment;
        return instanceId == null ? base : base + "#" + instanceId;
    }
}
