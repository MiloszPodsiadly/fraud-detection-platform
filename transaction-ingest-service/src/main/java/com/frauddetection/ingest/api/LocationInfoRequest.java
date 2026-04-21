package com.frauddetection.ingest.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LocationInfoRequest(
        @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
        @NotBlank @Size(max = 64) String region,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 16) String postalCode,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotBlank @Size(max = 64) String timezone,
        @NotNull Boolean highRiskCountry
) {
}
