package com.frauddetection.common.events.model;

public record LocationInfo(
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
