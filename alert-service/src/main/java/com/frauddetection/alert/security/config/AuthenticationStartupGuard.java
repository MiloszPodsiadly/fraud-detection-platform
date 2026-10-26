package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.DemoAuthFilter;
import com.frauddetection.alert.security.auth.JwtSecurityProperties;
import com.frauddetection.alert.runtime.InstitutionalModePolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AuthenticationStartupGuard implements ApplicationRunner {

    private final Environment environment;
    private final JwtDecoder jwtDecoder;
    private final DemoAuthFilter demoAuthFilter;
    private final boolean bankModeFailClosed;
    private final JwtSecurityProperties jwtProperties;
    private final boolean demoAuthEnabled;

    public AuthenticationStartupGuard(
            Environment environment,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<DemoAuthFilter> demoAuthFilter,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            JwtSecurityProperties jwtProperties,
            @Value("${app.security.demo-auth.enabled:false}") boolean demoAuthEnabled
    ) {
        this.environment = environment;
        this.jwtDecoder = jwtDecoder.getIfAvailable();
        this.demoAuthFilter = demoAuthFilter.getIfAvailable();
        this.bankModeFailClosed = bankModeFailClosed;
        this.jwtProperties = jwtProperties;
        this.demoAuthEnabled = demoAuthEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!prodLike()) {
            return;
        }
        require("app.security.jwt.required", "true", jwtProperties.required(), "institutional mode requires JWT resource-server authentication.");
        require("JwtDecoder", "present", jwtDecoder != null, "institutional mode must have a configured JWT decoder.");
        require("app.security.jwt.issuer-uri", "configured", StringUtils.hasText(jwtProperties.issuerUri()),
                "institutional mode requires an explicit trusted JWT issuer.");
        require("app.security.jwt.expected-audience", "configured", StringUtils.hasText(jwtProperties.expectedAudience()),
                "institutional mode requires an explicit JWT resource audience.");
        require("app.security.demo-auth.enabled", "false", !demoAuthEnabled, "demo/header authentication is local/dev only.");
        require("DemoAuthFilter", "absent", demoAuthFilter == null, "demo/header authentication filter must not be registered in institutional mode.");
    }

    private boolean prodLike() {
        return InstitutionalModePolicy.isInstitutionalMode(environment, bankModeFailClosed);
    }

    private void require(String setting, String required, boolean valid, String reason) {
        if (!valid) {
            throw new IllegalStateException("Authentication startup guard failed: setting="
                    + setting + "; required=" + required + "; reason=" + reason);
        }
    }
}
