package com.frauddetection.ingest.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record DeviceInfoRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String deviceId,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String fingerprint,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Fa-f0-9:.]+$|^\\d{1,3}(\\.\\d{1,3}){3}$") String ipAddress,
        @NotBlank @Size(max = 512) String userAgent,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9_:-]+$") String platform,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9_:-]+$") String browser,
        @NotNull Boolean trustedDevice,
        @NotNull Boolean proxyDetected,
        @NotNull Boolean vpnDetected,
        @Size(max = 50) Map<String, Object> attributes
) {
}
