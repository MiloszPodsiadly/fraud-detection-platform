package com.frauddetection.trustauthority;

import org.springframework.util.StringUtils;

record TrustAuthorityRequestCredentials(
        TrustAuthorityCallerIdentity caller,
        String signedAt,
        String signature
) {
    static TrustAuthorityRequestCredentials of(
            String serviceName,
            String environment,
            String instanceId,
            String signedAt,
            String signature
    ) {
        return new TrustAuthorityRequestCredentials(
                TrustAuthorityCallerIdentity.of(serviceName, environment, instanceId),
                StringUtils.hasText(signedAt) ? signedAt : null,
                signature
        );
    }
}
