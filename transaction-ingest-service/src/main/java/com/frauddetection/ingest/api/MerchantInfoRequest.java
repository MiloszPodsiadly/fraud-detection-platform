package com.frauddetection.ingest.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record MerchantInfoRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String merchantId,
        @NotBlank @Size(max = 128) String merchantName,
        @NotBlank @Size(max = 8) @Pattern(regexp = "^[A-Za-z0-9]+$") String merchantCategoryCode,
        @NotBlank @Size(max = 64) String merchantCategory,
        @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "^[A-Z]{2}$") String acquiringCountryCode,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9_:-]+$") String channel,
        @NotNull Boolean cardPresent,
        @Size(max = 50) Map<String, Object> attributes
) {
}
