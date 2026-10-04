package com.frauddetection.alert.outbox;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class FraudAlertOutboxBacklogMonitor {

    private static final List<FraudAlertOutboxStatus> UNRESOLVED = List.of(
            FraudAlertOutboxStatus.PENDING,
            FraudAlertOutboxStatus.PROCESSING,
            FraudAlertOutboxStatus.PUBLISH_ATTEMPTED,
            FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN,
            FraudAlertOutboxStatus.FAILED_TERMINAL
    );

    private final MongoTemplate mongoTemplate;
    private final AlertServiceMetrics metrics;

    public FraudAlertOutboxBacklogMonitor(MongoTemplate mongoTemplate, AlertServiceMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
    }

    public FraudAlertOutboxBacklogResponse snapshotAndRecord() {
        FraudAlertOutboxBacklogResponse response = snapshot();
        metrics.recordFraudAlertOutboxBacklog(response);
        return response;
    }

    public FraudAlertOutboxBacklogResponse snapshot() {
        FraudAlertOutboxRecord oldest = mongoTemplate.findOne(
                Query.query(Criteria.where("status").in(UNRESOLVED))
                        .with(Sort.by(Sort.Direction.ASC, "createdAt"))
                        .limit(1),
                FraudAlertOutboxRecord.class
        );
        Long oldestAge = oldest == null || oldest.getCreatedAt() == null
                ? null
                : Math.max(0L, Duration.between(oldest.getCreatedAt(), Instant.now()).toSeconds());
        return new FraudAlertOutboxBacklogResponse(
                count(FraudAlertOutboxStatus.PENDING),
                count(FraudAlertOutboxStatus.PROCESSING),
                count(FraudAlertOutboxStatus.PUBLISH_ATTEMPTED),
                count(FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN),
                count(FraudAlertOutboxStatus.FAILED_TERMINAL),
                oldestAge
        );
    }

    private long count(FraudAlertOutboxStatus status) {
        return mongoTemplate.count(Query.query(Criteria.where("status").is(status)), FraudAlertOutboxRecord.class);
    }
}
