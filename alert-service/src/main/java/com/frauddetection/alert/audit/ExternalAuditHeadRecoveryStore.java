package com.frauddetection.alert.audit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
class ExternalAuditHeadRecoveryStore implements ExternalAuditHeadRecoveryTransitions {

    static final String VERIFIED_REPAIRED = "VERIFIED_REPAIRED";

    private final MongoTemplate mongoTemplate;

    ExternalAuditHeadRecoveryStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void recordVerifiedRepair(String incidentId, Instant attemptedAt) {
        AuditDegradationEventDocument updated = updateUnresolved(
                incidentId,
                VERIFIED_REPAIRED,
                attemptedAt,
                true
        );
        if (updated == null && !isAlreadyVerified(incidentId)) {
            throw unavailable();
        }
    }

    @Override
    public void recordFailedRepair(String incidentId, String outcome, Instant attemptedAt) {
        AuditDegradationEventDocument updated = updateUnresolved(
                incidentId,
                outcome,
                attemptedAt,
                false
        );
        if (updated == null && !isAlreadyVerified(incidentId)) {
            throw unavailable();
        }
    }

    private AuditDegradationEventDocument updateUnresolved(
            String incidentId,
            String outcome,
            Instant attemptedAt,
            boolean resolved
    ) {
        try {
            Query query = Query.query(Criteria.where("auditId").is(incidentId)
                    .and("type").is(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY)
                    .and("resolved").ne(true));
            Update update = new Update()
                    .set("recoveryOutcome", outcome)
                    .set("recoveryAttemptedAt", attemptedAt)
                    .set("resolved", resolved);
            if (resolved) {
                update.unset("activeIncidentKey");
            }
            return mongoTemplate.findAndModify(
                    query,
                    update,
                    FindAndModifyOptions.options().returnNew(true),
                    AuditDegradationEventDocument.class
            );
        } catch (DataAccessException exception) {
            throw new AuditPersistenceUnavailableException(
                    "External HEAD repair evidence could not be persisted.",
                    exception
            );
        }
    }

    private boolean isAlreadyVerified(String incidentId) {
        try {
            Query query = Query.query(Criteria.where("auditId").is(incidentId)
                    .and("type").is(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY)
                    .and("resolved").is(true)
                    .and("recoveryOutcome").is(VERIFIED_REPAIRED));
            return mongoTemplate.exists(query, AuditDegradationEventDocument.class);
        } catch (DataAccessException exception) {
            throw new AuditPersistenceUnavailableException(
                    "External HEAD repair evidence could not be verified.",
                    exception
            );
        }
    }

    private AuditPersistenceUnavailableException unavailable() {
        return new AuditPersistenceUnavailableException(
                "External HEAD integrity evidence is unavailable.",
                null
        );
    }
}
