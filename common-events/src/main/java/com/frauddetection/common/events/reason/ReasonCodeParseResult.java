package com.frauddetection.common.events.reason;

public record ReasonCodeParseResult(
        ReasonCode reasonCode,
        ReasonCodeParseStatus status,
        String rawValue
) {
    public ReasonCodeParseResult {
        if (reasonCode == null) {
            reasonCode = ReasonCode.UNKNOWN;
        }
        if (status == null) {
            status = ReasonCodeParseStatus.UNSUPPORTED;
        }
    }

    public String wireValue() {
        return reasonCode.wireValue();
    }

    public boolean supported() {
        return status == ReasonCodeParseStatus.KNOWN || status == ReasonCodeParseStatus.LEGACY_MAPPED;
    }
}
