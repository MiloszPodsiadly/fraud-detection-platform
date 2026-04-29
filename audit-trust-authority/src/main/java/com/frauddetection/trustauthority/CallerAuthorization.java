package com.frauddetection.trustauthority;

import org.springframework.http.HttpStatus;

record CallerAuthorization(
        boolean tokenValid,
        boolean allowed,
        HttpStatus status,
        String reasonCode,
        int signRateLimitPerMinute,
        TrustAuthorityCallerIdentity identity
) {
    static CallerAuthorization success(int signRateLimitPerMinute, TrustAuthorityCallerIdentity identity) {
        return new CallerAuthorization(true, true, HttpStatus.OK, null, signRateLimitPerMinute, identity);
    }

    static CallerAuthorization failure(HttpStatus status, String reasonCode) {
        return new CallerAuthorization(false, false, status, reasonCode, 0, TrustAuthorityCallerIdentity.of(null, null, null));
    }
}
