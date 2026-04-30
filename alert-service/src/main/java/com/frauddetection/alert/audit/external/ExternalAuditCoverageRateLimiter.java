package com.frauddetection.alert.audit.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Component
public class ExternalAuditCoverageRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final int maxCostPerMinute;

    public ExternalAuditCoverageRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.audit.external-integrity.coverage.max-cost-per-minute:300}") int maxCostPerMinute
    ) {
        this.redisTemplate = redisTemplate;
        this.maxCostPerMinute = Math.max(1, Math.min(maxCostPerMinute, 10_000));
    }

    public boolean allow(String identity, int requestCost) {
        int cost = Math.max(1, Math.min(requestCost, 100));
        String key = "audit:coverage:rate:" + identityHash(identity);
        try {
            Long current = redisTemplate.opsForValue().increment(key, cost);
            if (current != null && current == cost) {
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
            return current != null && current <= maxCostPerMinute;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String identityHash(String identity) {
        String normalized = StringUtils.hasText(identity) ? identity.trim() : "unknown";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }
}
