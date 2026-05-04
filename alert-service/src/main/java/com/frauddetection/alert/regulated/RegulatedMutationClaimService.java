package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegulatedMutationClaimService {

    private final MongoTemplate mongoTemplate;
    private final Duration leaseDuration;

    public RegulatedMutationClaimService(
            MongoTemplate mongoTemplate,
            @Value("${app.regulated-mutation.lease-duration:PT30S}") Duration leaseDuration
    ) {
        this.mongoTemplate = mongoTemplate;
        this.leaseDuration = leaseDuration;
    }

    public <R, S> Optional<RegulatedMutationCommandDocument> claim(
            RegulatedMutationCommand<R, S> command,
            String idempotencyKey
    ) {
        if (command == null) {
            throw new IllegalArgumentException("Regulated mutation command is required.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Regulated mutation idempotency key is required.");
        }

        Instant now = Instant.now();
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
                .set("updated_at", now)
                .inc("attempt_count", 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                RegulatedMutationCommandDocument.class
        ));
    }
}
