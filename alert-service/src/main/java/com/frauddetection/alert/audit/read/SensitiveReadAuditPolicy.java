package com.frauddetection.alert.audit.read;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

@Component
public class SensitiveReadAuditPolicy {

    private final Environment environment;
    private final boolean bankModeFailClosed;
    private final boolean sensitiveReadsFailClosed;

    public SensitiveReadAuditPolicy(
            Environment environment,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.sensitive-reads.audit.fail-closed:false}") boolean sensitiveReadsFailClosed
    ) {
        this.environment = environment;
        this.bankModeFailClosed = bankModeFailClosed;
        this.sensitiveReadsFailClosed = sensitiveReadsFailClosed;
    }

    public boolean failClosed() {
        return sensitiveReadsFailClosed || bankModeFailClosed || prodLikeProfile();
    }

    public boolean prodLikeProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("staging")
                        || profile.equals("bank"));
    }
}
