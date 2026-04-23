package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.principal.AnalystPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

public interface AnalystPrincipalResolver {

    Optional<AnalystPrincipal> resolve(HttpServletRequest request);
}
