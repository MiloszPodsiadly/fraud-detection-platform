package com.frauddetection.alert.api;

public record ScoredMerchantInfoResponse(
        String merchantId,
        String merchantName,
        String merchantCategoryCode,
        String merchantCategory,
        String channel,
        String acquiringCountryCode
) {
}
