package com.frauddetection.common.events.model;

import java.util.List;
import java.util.Map;

public record CustomerContext(
        String customerId,
        String accountId,
        String segment,
        String emailDomain,
        Integer accountAgeDays,
        Boolean emailVerified,
        Boolean phoneVerified,
        String homeCountryCode,
        String preferredCurrency,
        List<String> knownDeviceIds,
        Map<String, Object> attributes
) {
}
