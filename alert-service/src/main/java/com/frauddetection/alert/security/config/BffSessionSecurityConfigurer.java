package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.BffLogoutSuccessHandler;
import com.frauddetection.alert.security.auth.OidcAnalystAuthoritiesMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.util.StringUtils;

class BffSessionSecurityConfigurer {

    void configure(
            HttpSecurity http,
            boolean bffEnabled,
            ObjectProvider<OidcAnalystAuthoritiesMapper> oidcAnalystAuthoritiesMapper,
            BffLogoutSuccessHandler bffLogoutSuccessHandler
    ) throws Exception {
        if (!bffEnabled) {
            http.csrf(AbstractHttpConfigurer::disable);
            return;
        }
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(this::isStatelessBearerRequest)
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userAuthoritiesMapper(oidcAnalystAuthoritiesMapper.getObject()))
                        .defaultSuccessUrl("/", true)
                )
                .logout(logout -> logout
                        .logoutUrl("/bff/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                        .logoutSuccessHandler(bffLogoutSuccessHandler)
                );
    }

    private boolean isStatelessBearerRequest(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return StringUtils.hasText(authorization)
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                && !hasSessionSignal(request);
    }

    private boolean hasSessionSignal(HttpServletRequest request) {
        return hasCookie(request, "JSESSIONID")
                || StringUtils.hasText(request.getRequestedSessionId())
                || hasCookieHeader(request, "JSESSIONID");
    }

    private boolean hasCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCookieHeader(HttpServletRequest request, String cookieName) {
        String cookieHeader = request.getHeader("Cookie");
        return StringUtils.hasText(cookieHeader)
                && cookieHeader.contains(cookieName + "=");
    }
}
