package com.frauddetection.alert.security.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class DemoAuthFilter extends OncePerRequestFilter {

    private final AnalystPrincipalResolver principalResolver;
    private final AnalystAuthenticationFactory authenticationFactory;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public DemoAuthFilter(
            AnalystPrincipalResolver principalResolver,
            AnalystAuthenticationFactory authenticationFactory,
            AuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.principalResolver = principalResolver;
        this.authenticationFactory = authenticationFactory;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            principalResolver.resolve(request)
                    .map(authenticationFactory::authenticated)
                    .ifPresent(authentication -> SecurityContextHolder.getContext().setAuthentication(authentication));
            filterChain.doFilter(request, response);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
