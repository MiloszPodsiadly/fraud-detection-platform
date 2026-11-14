package com.frauddetection.enricher.device;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceEpochDigestTest {
    @Test
    void digestPreservesTheLengthPrefixedUtf8Format() throws Exception {
        String malformedUtf16 = new String(new char[]{'x', '\uD800', 'y'});
        String longValue = "λ".repeat(2_048);
        String[] fields = {"alpha", "β", "🙂", malformedUtf16, "", longValue, "after-buffer-growth"};

        assertThat(DeviceEpoch.digest("test-domain", fields))
                .isEqualTo(previousDigestFormat("test-domain", fields));
    }

    private static String previousDigestFormat(String domain, String... fields) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, domain);
        for (String field : fields) { update(digest, field); }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        int length = encoded.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(encoded);
    }
}
