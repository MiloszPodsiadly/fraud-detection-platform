package com.frauddetection.common.events.merchant;

import java.nio.charset.StandardCharsets;

/** Shared UTF-8 byte-length framing for versioned merchant contract canonical bytes. */
final class MerchantCanonicalEncoding {
    private MerchantCanonicalEncoding() { }

    static void append(StringBuilder out, Object... fields) {
        for (Object field : fields) {
            String text = field == null ? null : field.toString();
            out.append(text == null ? -1 : text.getBytes(StandardCharsets.UTF_8).length).append(':');
            if (text != null) { out.append(text); }
        }
    }
}
