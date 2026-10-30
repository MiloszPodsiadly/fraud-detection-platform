package com.frauddetection.enricher.merchant;

import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Scope;
import org.bson.Document;

/** Versioned Mongo document mapping and identity validation, kept separate from transaction coordination. */
final class MerchantObservationDocumentCodec {
    private MerchantObservationDocumentCodec() { }

    static String eventId(Observation observation) {
        return observation.scope().institution() + ':' + observation.scope().source() + ':' + observation.sourceEvent();
    }

    static String scopeId(Scope scope) {
        return scope.issuer() + ':' + scope.institution() + ':' + scope.source() + ':' + scope.epoch() + ':' + scope.identityVersion();
    }

    static String inputCommitment(Observation observation) {
        return MerchantCommitment.sha256("MERCHANT_STORE_DIGEST_UNAVAILABLE", "merchant-observation/v2",
                observation.scope().issuer(), observation.scope().institution(), observation.scope().source(),
                observation.scope().epoch(), observation.scope().identityVersion(), observation.sourceEvent(), observation.transaction(),
                observation.occurrence(), observation.merchant(), observation.sequence(), observation.eventTime(), observation.sourceObservedAt(),
                observation.payloadCommitment());
    }

    static Document scopeDocument(Scope scope) {
        return new Document("schemaVersion", MongoMerchantObservationSchema.SCHEMA_VERSION).append("issuer", scope.issuer())
                .append("institution", scope.institution()).append("source", scope.source()).append("epoch", scope.epoch())
                .append("identityVersion", scope.identityVersion());
    }

    static Document observationDocument(Observation observation) {
        return scopeDocument(observation.scope()).append("_id", eventId(observation)).append("scope", scopeId(observation.scope()))
                .append("sourceEvent", observation.sourceEvent()).append("transaction", observation.transaction())
                .append("occurrence", observation.occurrence()).append("merchant", observation.merchant())
                .append("sequence", observation.sequence()).append("eventTime", java.util.Date.from(observation.eventTime()))
                .append("sourceObservedAt", java.util.Date.from(observation.sourceObservedAt()))
                .append("payloadCommitment", observation.payloadCommitment()).append("inputCommitment", inputCommitment(observation));
    }

    static Scope decodeScope(Document row) {
        if (!Integer.valueOf(MongoMerchantObservationSchema.SCHEMA_VERSION).equals(row.get("schemaVersion"))) {
            throw failure("SCHEMA_VERSION");
        }
        return new Scope(row.getString("issuer"), row.getString("institution"), row.getString("source"), row.getString("epoch"),
                row.getInteger("identityVersion"));
    }

    static Observation decodeObservation(Document row) {
        try {
            Observation observation = new Observation(decodeScope(row), row.getString("sourceEvent"), row.getString("transaction"),
                    row.getString("occurrence"), row.getString("merchant"), row.getLong("sequence"), row.getDate("eventTime").toInstant(),
                    row.getDate("sourceObservedAt").toInstant(), row.getString("payloadCommitment"));
            if (!eventId(observation).equals(row.getString("_id")) || !scopeId(observation.scope()).equals(row.getString("scope"))
                    || !inputCommitment(observation).equals(row.getString("inputCommitment"))) {
                throw failure("CORRUPT_OBSERVATION");
            }
            return observation;
        } catch (RuntimeException exception) { throw failure("CORRUPT_OBSERVATION"); }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException("MERCHANT_STORE_" + code);
    }
}
