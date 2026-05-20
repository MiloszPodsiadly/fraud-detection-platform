package com.frauddetection.alert.suspicious.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.suspicious-transactions.summary")
public class SuspiciousTransactionSummaryProperties {

    private Duration cacheTtl = Duration.ofSeconds(30);

    public Duration cacheTtl() {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("app.suspicious-transactions.summary.cache-ttl must be positive");
        }
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }
}
