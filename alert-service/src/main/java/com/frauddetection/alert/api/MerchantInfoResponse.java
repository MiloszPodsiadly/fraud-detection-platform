package com.frauddetection.alert.api;

import java.util.Map;

public record MerchantInfoResponse(
        String merchantId,
        String merchantName,
        String merchantCategoryCode,
        String merchantCategory,
        String acquiringCountryCode,
        String channel,
        Boolean cardPresent,
        Map<String, Object> attributes
) {
}
