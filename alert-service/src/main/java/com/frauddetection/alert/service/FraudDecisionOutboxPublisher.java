package com.frauddetection.alert.service;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class FraudDecisionOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(FraudDecisionOutboxPublisher.class);

    private final AlertRepository alertRepository;
    private final FraudDecisionEventPublisher publisher;

    public FraudDecisionOutboxPublisher(AlertRepository alertRepository, FraudDecisionEventPublisher publisher) {
        this.alertRepository = alertRepository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${app.alert.decision-outbox.publish-delay-ms:5000}")
    public void publishPending() {
        publishPending(100);
    }

    public int publishPending(int limit) {
        List<AlertDocument> pending = alertRepository.findTop100ByDecisionOutboxStatusOrderByDecidedAtAsc(DecisionOutboxStatus.PENDING);
        int published = 0;
        for (AlertDocument document : pending.stream().limit(Math.max(1, Math.min(limit, 100))).toList()) {
            if (document.getDecisionOutboxEvent() == null) {
                continue;
            }
            try {
                publisher.publish(document.getDecisionOutboxEvent());
                document.setDecisionOutboxStatus(DecisionOutboxStatus.PUBLISHED);
                document.setDecisionOutboxPublishedAt(Instant.now());
                document.setDecisionOutboxFailureReason(null);
                alertRepository.save(document);
                published++;
            } catch (RuntimeException exception) {
                document.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
                document.setDecisionOutboxAttempts(document.getDecisionOutboxAttempts() + 1);
                document.setDecisionOutboxLastAttemptAt(Instant.now());
                document.setDecisionOutboxFailureReason("PUBLISH_FAILED");
                alertRepository.save(document);
                log.warn("Fraud decision outbox publish failed: reason=PUBLISH_FAILED");
            }
        }
        return published;
    }
}
