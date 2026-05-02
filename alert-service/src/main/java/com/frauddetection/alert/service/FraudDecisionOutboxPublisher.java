package com.frauddetection.alert.service;

import com.frauddetection.alert.outbox.OutboxPublisherCoordinator;
import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class FraudDecisionOutboxPublisher {

    private final OutboxPublisherCoordinator coordinator;

    @Autowired
    public FraudDecisionOutboxPublisher(OutboxPublisherCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public FraudDecisionOutboxPublisher(
            AlertRepository ignoredRepository,
            FraudDecisionEventPublisher publisher,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics,
            Duration leaseDuration,
            int maxAttempts
    ) {
        this(new OutboxPublisherCoordinator(publisher, mongoTemplate, metrics, leaseDuration, maxAttempts));
    }

    @Scheduled(fixedDelayString = "${app.alert.decision-outbox.publish-delay-ms:5000}")
    public void publishPending() {
        publishPending(100);
    }

    public int publishPending(int limit) {
        return coordinator.publishPending(limit);
    }
}
