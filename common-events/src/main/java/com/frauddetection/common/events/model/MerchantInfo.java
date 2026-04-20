package com.frauddetection.common.events.model;

import java.util.Map;

public record MerchantInfo(
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
