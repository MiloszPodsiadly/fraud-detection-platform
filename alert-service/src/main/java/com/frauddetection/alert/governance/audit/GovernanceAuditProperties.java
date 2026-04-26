package com.frauddetection.alert.governance.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.governance.audit")
public record GovernanceAuditProperties(
        URI mlGovernanceBaseUrl,
        int historyLimit,
        Duration mlLookupTimeout
) {
    public GovernanceAuditProperties {
        if (mlGovernanceBaseUrl == null) {
            mlGovernanceBaseUrl = URI.create("http://localhost:8090");
        }
        if (historyLimit <= 0) {
            historyLimit = 50;
        }
        if (mlLookupTimeout == null || mlLookupTimeout.isNegative() || mlLookupTimeout.isZero()) {
            mlLookupTimeout = Duration.ofSeconds(2);
        }
    }
}
