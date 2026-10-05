package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "app.engine-intelligence.pending-projection",
        name = "enabled",
        havingValue = "true"
)
public class EngineIntelligencePendingProjectionWorker {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligencePendingProjectionWorker.class);
    private static final String RETRY_EXHAUSTED = "BASELINE_OCCURRENCE_PENDING_RETRY_EXHAUSTED";
    private static final String MAX_AGE_EXCEEDED = "BASELINE_OCCURRENCE_PENDING_MAX_AGE_EXCEEDED";
    private static final String PERMANENT_INVALID = "PERMANENT_INVALID_DIAGNOSTICS";
    private static final String PAYLOAD_CONFLICT = "SOURCE_OCCURRENCE_PAYLOAD_CONFLICT";

    private final MongoTemplate mongoTemplate;
    private final EngineIntelligenceProjectionService projectionService;
    private final EngineIntelligencePendingProjectionProperties properties;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public EngineIntelligencePendingProjectionWorker(
            MongoTemplate mongoTemplate,
            EngineIntelligenceProjectionService projectionService,
            EngineIntelligencePendingProjectionProperties properties,
            AlertServiceMetrics metrics
    ) {
        this(mongoTemplate, projectionService, properties, metrics, Clock.systemUTC());
    }

    EngineIntelligencePendingProjectionWorker(
            MongoTemplate mongoTemplate,
            EngineIntelligenceProjectionService projectionService,
            EngineIntelligencePendingProjectionProperties properties,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.mongoTemplate = mongoTemplate;
        this.projectionService = projectionService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.engine-intelligence.pending-projection.fixed-delay:PT5S}")
    public void recoverPendingProjections() {
        Instant now = clock.instant();
        finalizeExpired(now);
        for (int processed = 0; processed < properties.batchSize(); processed++) {
            EngineIntelligencePendingProjection work = claimNext(now);
            if (work == null) {
                break;
            }
            process(work);
        }
        recordBacklog(clock.instant());
    }

    private EngineIntelligencePendingProjection claimNext(Instant now) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("attempts").lt(properties.maxAttempts()),
                Criteria.where("createdAt").gt(now.minus(properties.maxAge())),
                new Criteria().orOperator(
                        new Criteria().andOperator(
                                Criteria.where("status").is(EngineIntelligencePendingProjectionStatus.PENDING),
                                Criteria.where("availableAt").lte(now)
                        ),
                        new Criteria().andOperator(
                                Criteria.where("status").is(EngineIntelligencePendingProjectionStatus.PROCESSING),
                                Criteria.where("leaseExpiresAt").lte(now)
                        )
                )
        )).with(Sort.by(Sort.Direction.ASC, "createdAt"));
        Update update = new Update()
                .set("status", EngineIntelligencePendingProjectionStatus.PROCESSING)
                .set("leaseToken", UUID.randomUUID().toString())
                .set("leaseExpiresAt", now.plus(properties.leaseDuration()))
                .set("updatedAt", now)
                .inc("attempts", 1);
        return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                EngineIntelligencePendingProjection.class
        );
    }

    private void process(EngineIntelligencePendingProjection work) {
        try {
            EngineIntelligenceProjectionResult result = projectionService.projectDeferredOccurrence(
                    work.getTransactionId(),
                    work.getSourceEventId(),
                    work.getSourceEventCreatedAt(),
                    work.getSourceEventFingerprint(),
                    work.getEngineIntelligence()
            );
            if (result.projection().isPresent()
                    || result.omissionReason().orElse(null)
                    == EngineIntelligenceProjectionOmissionReason.SCORING_OCCURRENCE_NOT_CURRENT) {
                complete(work);
            } else {
                markUnresolved(work, PERMANENT_INVALID);
            }
        } catch (EngineIntelligenceProjectionService.CurrentScoringOccurrencePendingException exception) {
            reschedule(work, "BASELINE_OCCURRENCE_PENDING");
        } catch (EngineIntelligenceProjectionService.SourceOccurrencePayloadConflictException exception) {
            markUnresolved(work, PAYLOAD_CONFLICT);
        } catch (EngineIntelligenceProjectionService.ProjectionStoreUnavailableException exception) {
            reschedule(work, "TRANSIENT_STORAGE_UNAVAILABLE");
        } catch (RuntimeException exception) {
            reschedule(work, "TRANSIENT_PROCESSING_FAILURE");
        }
    }

    private void complete(EngineIntelligencePendingProjection work) {
        mongoTemplate.remove(leased(work), EngineIntelligencePendingProjection.class);
    }

    private void reschedule(EngineIntelligencePendingProjection work, String reason) {
        Instant now = clock.instant();
        if (work.getAttempts() >= properties.maxAttempts()
                || !work.getCreatedAt().plus(properties.maxAge()).isAfter(now)) {
            markUnresolved(work, work.getAttempts() >= properties.maxAttempts() ? RETRY_EXHAUSTED : MAX_AGE_EXCEEDED);
            return;
        }
        mongoTemplate.updateFirst(
                leased(work),
                new Update()
                        .set("status", EngineIntelligencePendingProjectionStatus.PENDING)
                        .set("availableAt", now.plus(properties.retryDelay()))
                        .set("updatedAt", now)
                        .set("lastReason", reason)
                        .unset("leaseToken")
                        .unset("leaseExpiresAt"),
                EngineIntelligencePendingProjection.class
        );
    }

    private void markUnresolved(EngineIntelligencePendingProjection work, String reason) {
        long modified = mongoTemplate.updateFirst(
                leased(work),
                unresolvedUpdate(clock.instant(), reason),
                EngineIntelligencePendingProjection.class
        ).getModifiedCount();
        if (modified == 1) {
            log.atError()
                    .addKeyValue("sourceEventId", work.getSourceEventId())
                    .addKeyValue("transactionId", work.getTransactionId())
                    .addKeyValue("reason", reason)
                    .log("Engine Intelligence projection requires operator resolution.");
        }
    }

    private void finalizeExpired(Instant now) {
        UpdateResult retryExhausted = mongoTemplate.updateMulti(
                Query.query(new Criteria().andOperator(
                        Criteria.where("status").in(
                                EngineIntelligencePendingProjectionStatus.PENDING,
                                EngineIntelligencePendingProjectionStatus.PROCESSING
                        ),
                        Criteria.where("attempts").gte(properties.maxAttempts()),
                        new Criteria().orOperator(
                                Criteria.where("status").is(EngineIntelligencePendingProjectionStatus.PENDING),
                                Criteria.where("leaseExpiresAt").lte(now)
                        )
                )),
                unresolvedUpdate(now, RETRY_EXHAUSTED),
                EngineIntelligencePendingProjection.class
        );
        UpdateResult overAge = mongoTemplate.updateMulti(
                Query.query(new Criteria().andOperator(
                        Criteria.where("status").in(
                                EngineIntelligencePendingProjectionStatus.PENDING,
                                EngineIntelligencePendingProjectionStatus.PROCESSING
                        ),
                        Criteria.where("createdAt").lte(now.minus(properties.maxAge())),
                        new Criteria().orOperator(
                                Criteria.where("status").is(EngineIntelligencePendingProjectionStatus.PENDING),
                                Criteria.where("leaseExpiresAt").lte(now)
                        )
                )),
                unresolvedUpdate(now, MAX_AGE_EXCEEDED),
                EngineIntelligencePendingProjection.class
        );
        long newlyUnresolved = retryExhausted.getModifiedCount() + overAge.getModifiedCount();
        if (newlyUnresolved > 0) {
            log.atError()
                    .addKeyValue("count", newlyUnresolved)
                    .log("Engine Intelligence pending projections require operator resolution.");
        }
    }

    private Update unresolvedUpdate(Instant now, String reason) {
        return new Update()
                .set("status", EngineIntelligencePendingProjectionStatus.UNRESOLVED)
                .set("updatedAt", now)
                .set("lastReason", reason)
                .unset("leaseToken")
                .unset("leaseExpiresAt");
    }

    private Query leased(EngineIntelligencePendingProjection work) {
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(work.getSourceEventId()),
                Criteria.where("status").is(EngineIntelligencePendingProjectionStatus.PROCESSING),
                Criteria.where("leaseToken").is(work.getLeaseToken()),
                Criteria.where("leaseExpiresAt").gt(clock.instant())
        ));
    }

    private void recordBacklog(Instant now) {
        long pending = mongoTemplate.count(
                Query.query(Criteria.where("status").in(
                        EngineIntelligencePendingProjectionStatus.PENDING,
                        EngineIntelligencePendingProjectionStatus.PROCESSING
                )),
                EngineIntelligencePendingProjection.class
        );
        long unresolved = mongoTemplate.count(
                Query.query(Criteria.where("status").is(EngineIntelligencePendingProjectionStatus.UNRESOLVED)),
                EngineIntelligencePendingProjection.class
        );
        EngineIntelligencePendingProjection oldest = mongoTemplate.findOne(
                Query.query(Criteria.where("status").in(
                        EngineIntelligencePendingProjectionStatus.PENDING,
                        EngineIntelligencePendingProjectionStatus.PROCESSING
                )).with(Sort.by(Sort.Direction.ASC, "createdAt")),
                EngineIntelligencePendingProjection.class
        );
        long oldestAge = oldest == null || oldest.getCreatedAt() == null
                ? 0L
                : Math.max(0L, Duration.between(oldest.getCreatedAt(), now).toSeconds());
        metrics.recordDiagnosticPendingProjectionBacklog(pending, unresolved, oldestAge);
    }
}
