package com.frauddetection.common.events.source;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Signed claims; constructing this value does not authenticate or admit a source. */
public record SourceScope(String issuer, String institution, String source, String sourceEpoch,
                          String signingKeyVersion, String sourceEventId, Instant eventTime,
                          String purpose, String audience, String payloadCommitment) {
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern PAYLOAD_COMMITMENT = Pattern.compile("[0-9a-f]{64}");

    public SourceScope {
        requireReference(issuer);
        requireReference(institution);
        requireReference(source);
        requireReference(sourceEpoch);
        requireReference(signingKeyVersion);
        requireReference(sourceEventId);
        requireReference(purpose);
        requireReference(audience);
        Objects.requireNonNull(eventTime, "SOURCE_EVENT_TIME_REQUIRED");
        if (payloadCommitment == null || !PAYLOAD_COMMITMENT.matcher(payloadCommitment).matches()) {
            throw new IllegalArgumentException("SOURCE_PAYLOAD_COMMITMENT_INVALID");
        }
    }

    static void requireReference(String value) {
        if (value == null || !REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("SOURCE_REFERENCE_INVALID");
        }
    }

    @Override public String toString() { return "SourceScope[redacted]"; }
}
