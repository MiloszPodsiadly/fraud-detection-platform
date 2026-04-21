package com.frauddetection.ingest.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CustomerContextRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String customerId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String accountId,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9_:-]+$") String segment,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$") String emailDomain,
        @NotNull @PositiveOrZero Integer accountAgeDays,
        @NotNull Boolean emailVerified,
        @NotNull Boolean phoneVerified,
        @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "^[A-Z]{2}$") String homeCountryCode,
        @NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$") String preferredCurrency,
        @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String> knownDeviceIds,
        @Size(max = 50) Map<String, Object> attributes
) {
}
