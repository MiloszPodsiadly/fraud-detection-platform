package com.frauddetection.alert.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

@Service
public class SharedIdempotencyKeyPolicy {

    private static final int MAX_LENGTH = 128;
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9._:-]+$");

    public String normalizeRequired(String rawKey) {
        if (rawKey == null) {
            throw new SharedMissingIdempotencyKeyException();
        }
        String normalized = rawKey.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new SharedMissingIdempotencyKeyException();
        }
        if (normalized.length() > MAX_LENGTH
                || !ALLOWED.matcher(normalized).matches()
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new SharedInvalidIdempotencyKeyException();
        }
        return normalized;
    }

    public String hashKey(String rawKey) {
        return IdempotencyCanonicalHasher.hash(normalizeRequired(rawKey));
    }
}
