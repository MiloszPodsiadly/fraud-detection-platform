package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;

public record ModelPromotionEvaluationWindow(String fromInclusive, String toInclusive) {

    public ModelPromotionEvaluationWindow {
        var from = GovernanceTimestampContract.parse(fromInclusive, "fromInclusive");
        var to = GovernanceTimestampContract.parse(toInclusive, "toInclusive");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("fromInclusive must not be after toInclusive");
        }
    }
}
