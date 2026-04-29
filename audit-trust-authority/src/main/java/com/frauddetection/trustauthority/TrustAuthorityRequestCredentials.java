package com.frauddetection.trustauthority;

import org.springframework.util.StringUtils;

record TrustAuthorityRequestCredentials(
        TrustAuthorityCallerIdentity caller,
        String requestId,
        String signedAt,
        String signature,
        String bearerToken
) {
    static TrustAuthorityRequestCredentials of(
            String serviceName,
            String environment,
            String instanceId,
            String requestId,
            String signedAt,
            String signature
    ) {
        return of(serviceName, environment, instanceId, requestId, signedAt, signature, null);
    }

    static TrustAuthorityRequestCredentials of(
            String serviceName,
            String environment,
            String instanceId,
            String requestId,
            String signedAt,
            String signature,
            String authorization
    ) {
        return new TrustAuthorityRequestCredentials(
                TrustAuthorityCallerIdentity.of(serviceName, environment, instanceId),
                StringUtils.hasText(requestId) ? requestId : null,
                StringUtils.hasText(signedAt) ? signedAt : null,
                signature,
                bearerToken(authorization)
        );
    }

    private static String bearerToken(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return StringUtils.hasText(token) ? token : null;
    }
}
