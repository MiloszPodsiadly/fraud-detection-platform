package com.frauddetection.alert.api;

import java.util.Map;

public record DeviceInfoResponse(
        String deviceId,
        String fingerprint,
        String ipAddress,
        String userAgent,
        String platform,
        String browser,
        Boolean trustedDevice,
        Boolean proxyDetected,
        Boolean vpnDetected,
        Map<String, Object> attributes
) {
}
