package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.DemoAuthFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

@Component
public class AuthenticationStartupGuard implements ApplicationRunner {

    private final Environment environment;
    private final JwtDecoder jwtDecoder;
    private final DemoAuthFilter demoAuthFilter;
    private final boolean bankModeFailClosed;
    private final boolean jwtRequired;
    private final boolean demoAuthEnabled;

    public AuthenticationStartupGuard(
            Environment environment,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<DemoAuthFilter> demoAuthFilter,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.security.jwt.required:false}") boolean jwtRequired,
            @Value("${app.security.demo-auth.enabled:false}") boolean demoAuthEnabled
    ) {
        this.environment = environment;
        this.jwtDecoder = jwtDecoder.getIfAvailable();
        this.demoAuthFilter = demoAuthFilter.getIfAvailable();
        this.bankModeFailClosed = bankModeFailClosed;
        this.jwtRequired = jwtRequired;
        this.demoAuthEnabled = demoAuthEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!prodLike()) {
            return;
        }
        require("app.security.jwt.required", "true", jwtRequired, "prod/staging/bank profiles require JWT resource-server authentication.");
        require("JwtDecoder", "present", jwtDecoder != null, "prod/staging/bank profiles must have a configured JWT decoder.");
        require("app.security.demo-auth.enabled", "false", !demoAuthEnabled, "demo/header authentication is local/dev only.");
        require("DemoAuthFilter", "absent", demoAuthFilter == null, "demo/header authentication filter must not be registered in prod/staging/bank.");
    }

    private boolean prodLike() {
        return bankModeFailClosed || Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("staging")
                        || profile.equals("bank"));
    }

    private void require(String setting, String required, boolean valid, String reason) {
        if (!valid) {
            throw new IllegalStateException("FDP-27 authentication startup guard failed: setting="
                    + setting + "; required=" + required + "; reason=" + reason);
        }
    }
}
