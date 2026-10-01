package com.frauddetection.alert.service;

import com.frauddetection.alert.outbox.OutboxPublisherCoordinator;
import com.frauddetection.alert.outbox.TransactionalOutboxRuntimeReadiness;
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
    private final TransactionalOutboxRuntimeReadiness runtimeReadiness;

    @Autowired
    public FraudDecisionOutboxPublisher(
            OutboxPublisherCoordinator coordinator,
            TransactionalOutboxRuntimeReadiness runtimeReadiness
    ) {
        this.coordinator = coordinator;
        this.runtimeReadiness = runtimeReadiness;
    }

    public FraudDecisionOutboxPublisher(
            AlertRepository ignoredRepository,
            FraudDecisionEventPublisher publisher,
            MongoTemplate mongoTemplate,
            AlertServiceMetrics metrics,
            Duration leaseDuration,
            int maxAttempts,
            TransactionalOutboxRuntimeReadiness runtimeReadiness
    ) {
        this(new OutboxPublisherCoordinator(publisher, mongoTemplate, metrics, leaseDuration, maxAttempts), runtimeReadiness);
    }

    @Scheduled(fixedDelayString = "${app.outbox.publisher.delay-ms:5000}")
    public void publishPending() {
        publishPending(100);
    }

    public int publishPending(int limit) {
        runtimeReadiness.requireReady();
        return coordinator.publishPending(limit);
    }
}
