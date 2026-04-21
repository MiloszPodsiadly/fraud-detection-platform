package com.frauddetection.alert.mapper;

import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class FraudAlertEventMapper {

    public FraudAlertEvent toEvent(AlertCase alertCase) {
        return new FraudAlertEvent(
                UUID.randomUUID().toString(),
                alertCase.alertId(),
                alertCase.transactionId(),
                alertCase.customerId(),
                alertCase.correlationId(),
                Instant.now(),
                alertCase.alertTimestamp(),
                alertCase.riskLevel(),
                alertCase.fraudScore(),
                alertCase.alertStatus(),
                alertCase.alertReason(),
                alertCase.reasonCodes(),
                alertCase.transactionAmount(),
                alertCase.merchantInfo(),
                alertCase.deviceInfo(),
                alertCase.locationInfo(),
                alertCase.customerContext(),
                alertCase.scoreDetails(),
                alertCase.featureSnapshot()
        );
    }
}
