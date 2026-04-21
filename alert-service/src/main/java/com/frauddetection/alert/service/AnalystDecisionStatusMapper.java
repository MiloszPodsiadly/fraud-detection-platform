package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Component;

@Component
public class AnalystDecisionStatusMapper {

    public AlertStatus toAlertStatus(SubmitAnalystDecisionRequest request) {
        return switch (request.decision()) {
            case CONFIRMED_FRAUD, MARKED_LEGITIMATE -> AlertStatus.RESOLVED;
            case REQUIRE_MORE_EVIDENCE -> AlertStatus.IN_REVIEW;
            case ESCALATED -> AlertStatus.ESCALATED;
        };
    }
}
