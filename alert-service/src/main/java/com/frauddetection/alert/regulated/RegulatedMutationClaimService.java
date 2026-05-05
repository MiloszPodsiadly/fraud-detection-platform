package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegulatedMutationClaimService {

    private final MongoTemplate mongoTemplate;
    private final Duration leaseDuration;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    public RegulatedMutationClaimService(
            MongoTemplate mongoTemplate,
            @Value("${app.regulated-mutation.lease-duration:PT30S}") Duration leaseDuration
    ) {
        this(mongoTemplate, leaseDuration, null, Clock.systemUTC());
    }

    @Autowired
    public RegulatedMutationClaimService(
            MongoTemplate mongoTemplate,
            @Value("${app.regulated-mutation.lease-duration:PT30S}") Duration leaseDuration,
            AlertServiceMetrics metrics
    ) {
        this(mongoTemplate, leaseDuration, metrics, Clock.systemUTC());
    }

    RegulatedMutationClaimService(
            MongoTemplate mongoTemplate,
            Duration leaseDuration,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.mongoTemplate = mongoTemplate;
        this.leaseDuration = leaseDuration;
        this.metrics = metrics;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public <R, S> Optional<RegulatedMutationClaimToken> claim(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey
    ) {
        if (command == null) {
            throw new IllegalArgumentException("Regulated mutation command is required.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Regulated mutation idempotency key is required.");
        }

        Instant now = clock.instant();
        String leaseOwner = UUID.randomUUID().toString();
        Criteria claimable = new Criteria().orOperator(
                Criteria.where("execution_status").is(RegulatedMutationExecutionStatus.NEW),
                new Criteria().andOperator(
                        Criteria.where("execution_status").is(RegulatedMutationExecutionStatus.PROCESSING),
                        Criteria.where("lease_expires_at").lte(now)
                )
        );
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("idempotency_key").is(idempotencyKey),
                Criteria.where("request_hash").is(command.requestHash()),
                claimable
        ));
        Update update = new Update()
                .set("execution_status", RegulatedMutationExecutionStatus.PROCESSING)
                .set("lease_owner", leaseOwner)
                .set("lease_expires_at", now.plus(leaseDuration))
                .set("last_heartbeat_at", now)
                .set("lease_renewal_count", 0)
                .set("lease_budget_started_at", now)
                .set("last_lease_renewed_at", null)
                .set("updated_at", now)
                .inc("attempt_count", 1);
        RegulatedMutationCommandDocument claimed = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                RegulatedMutationCommandDocument.class
        );
        if (claimed == null) {
            return Optional.empty();
        }
        if (claimed.getLeaseOwner() == null || claimed.getLeaseOwner().isBlank()) {
            claimed.setLeaseOwner(leaseOwner);
        }
        if (claimed.getLeaseExpiresAt() == null) {
            claimed.setLeaseExpiresAt(now.plus(leaseDuration));
        }
        if (claimed.getExecutionStatus() == null) {
            claimed.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        }
        if (metrics != null && claimed.getAttemptCount() > 1) {
            metrics.recordRegulatedMutationLeaseTakeover(
                    claimed.mutationModelVersionOrLegacy(),
                    claimed.getState()
            );
        }
        return Optional.of(new RegulatedMutationClaimToken(
                claimed.getId(),
                claimed.getLeaseOwner(),
                claimed.getLeaseExpiresAt(),
                now,
                claimed.getAttemptCount(),
                claimed.mutationModelVersionOrLegacy(),
                claimed.getState(),
                claimed.getExecutionStatus()
        ));
    }
}
