package com.frauddetection.alert.messaging;

import com.frauddetection.common.events.contract.FraudDecisionEvent;

public interface FraudDecisionEventPublisher {

    void publish(FraudDecisionEvent event);
}
