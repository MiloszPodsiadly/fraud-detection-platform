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

    private static final String JSESSIONID_COOKIE = "JSESSIONID";
    private static final String XSRF_TOKEN_COOKIE = "XSRF-TOKEN";

    @SuppressWarnings("java:S4502")
    void configure(
            HttpSecurity http,
            boolean bffEnabled,
            ObjectProvider<OidcAnalystAuthoritiesMapper> oidcAnalystAuthoritiesMapper,
            BffLogoutSuccessHandler bffLogoutSuccessHandler
    ) throws Exception {
        if (!bffEnabled) {
            configureStatelessApiCsrf(http);
            return;
        }
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .ignoringRequestMatchers(this::isCsrfIgnoredRequest)
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userAuthoritiesMapper(oidcAnalystAuthoritiesMapper.getObject()))
                        .defaultSuccessUrl("/", true)
                )
                .logout(logout -> logout
                        .logoutUrl("/bff/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies(JSESSIONID_COOKIE, XSRF_TOKEN_COOKIE)
                        .logoutSuccessHandler(bffLogoutSuccessHandler)
                );
    }

    @SuppressWarnings("java:S4502")
    private void configureStatelessApiCsrf(HttpSecurity http) {
        http.csrf(AbstractHttpConfigurer::disable);
    }

    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(true));
        return repository;
    }

    private boolean isStatelessBearerRequest(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return StringUtils.hasText(authorization)
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                && !hasSessionSignal(request);
    }

    private boolean isCsrfIgnoredRequest(HttpServletRequest request) {
        return isStatelessBearerRequest(request);
    }

    private boolean hasSessionSignal(HttpServletRequest request) {
        return hasCookie(request, JSESSIONID_COOKIE)
                || hasCookieHeader(request, JSESSIONID_COOKIE);
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
