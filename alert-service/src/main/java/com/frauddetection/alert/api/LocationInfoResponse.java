package com.frauddetection.alert.api;

public record LocationInfoResponse(
        String countryCode,
        String region,
        String city,
        String postalCode,
        Double latitude,
        Double longitude,
        String timezone,
        Boolean highRiskCountry
) {
}
