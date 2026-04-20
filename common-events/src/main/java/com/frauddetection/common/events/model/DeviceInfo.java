package com.frauddetection.common.events.model;

import java.util.Map;

public record DeviceInfo(
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
