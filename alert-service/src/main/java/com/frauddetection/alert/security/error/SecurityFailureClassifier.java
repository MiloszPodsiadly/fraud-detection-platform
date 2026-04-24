package com.frauddetection.alert.security.error;

import com.frauddetection.alert.security.auth.DemoAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;

public final class SecurityFailureClassifier {

    private SecurityFailureClassifier() {
    }

    public static String authType(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "jwt";
        }
        if (StringUtils.hasText(request.getHeader(DemoAuthHeaders.USER_ID))) {
            return "demo";
        }
        return "anonymous";
    }

    public static String authenticationFailureReason(HttpServletRequest request, AuthenticationException exception) {
        String authType = authType(request);
        if ("anonymous".equals(authType)) {
            return "missing_credentials";
        }
        if ("demo".equals(authType)) {
            return "invalid_demo_auth";
        }
        if ("jwt".equals(authType)) {
            return "invalid_jwt";
        }
        return "invalid_credentials";
    }

    public static String accessDeniedReason(Authentication authentication) {
        return authentication == null ? "missing_authority" : "insufficient_authority";
    }

    public static String actorType(Authentication authentication) {
        return authentication == null ? "anonymous" : "authenticated";
    }
}
