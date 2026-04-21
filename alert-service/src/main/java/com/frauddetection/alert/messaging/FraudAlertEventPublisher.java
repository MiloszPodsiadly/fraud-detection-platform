package com.frauddetection.alert.messaging;

import com.frauddetection.common.events.contract.FraudAlertEvent;

public interface FraudAlertEventPublisher {

    void publish(FraudAlertEvent event);
}
