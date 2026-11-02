package com.frauddetection.enricher.merchant;

import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Scope;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned Mongo document mapping and identity validation, kept separate from transaction coordination. */
final class MerchantObservationDocumentCodec {
    record MembershipCheckpoint(Scope scope, String id, String sourceEvent, String merchant, long sequence,
                                Instant eventTime, String inputCommitment, String previousId,
                                Long previousSequence, Instant previousEventTime, String previousCommitment,
                                String commitment) {
        @Override public String toString() { return "MerchantMembershipCheckpoint[redacted]"; }
    }
    record MembershipAnchor(String id, long sequence, Instant eventTime, String commitment) {
        @Override public String toString() { return "MerchantMembershipAnchor[redacted]"; }
    }
    record MembershipTip(MembershipCheckpoint checkpoint, MembershipAnchor compactedCheckpoint, boolean compacted) {
        @Override public String toString() { return "MerchantMembershipTip[redacted]"; }
    }

    private MerchantObservationDocumentCodec() { }

    static String eventId(Observation observation) {
        return eventId(observation.scope(), observation.sourceEvent());
    }

    static String eventId(Scope scope, String sourceEvent) {
        return MerchantCommitment.sha256("MERCHANT_STORE_DIGEST_UNAVAILABLE", "merchant-observation-id/v1",
                scope.issuer(), scope.institution(), scope.source(), scope.epoch(), scope.identityVersion(), sourceEvent);
    }

    static String scopeId(Scope scope) {
        return scope.issuer() + ':' + scope.institution() + ':' + scope.source() + ':' + scope.epoch() + ':' + scope.identityVersion();
    }

    static String membershipTipId(Scope scope, String merchant) {
        return MerchantCommitment.sha256("MERCHANT_STORE_DIGEST_UNAVAILABLE", "merchant-membership-tip/v1",
                scope.issuer(), scope.institution(), scope.source(), scope.epoch(), scope.identityVersion(), merchant);
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

    static Document membershipDocument(Observation observation, MembershipCheckpoint previous) {
        if (previous != null && (!previous.scope().equals(observation.scope())
                || !previous.merchant().equals(observation.merchant())
                || previous.sequence() >= observation.sequence())) {
            throw failure("MEMBERSHIP_ORDER");
        }
        String id = eventId(observation);
        String commitment = membershipCommitment(observation.scope(), id, observation.sourceEvent(), observation.merchant(),
                observation.sequence(), observation.eventTime(), inputCommitment(observation),
                previous == null ? null : previous.id(), previous == null ? null : previous.sequence(),
                previous == null ? null : previous.eventTime(), previous == null ? null : previous.commitment());
        return scopeDocument(observation.scope()).append("_id", id).append("scope", scopeId(observation.scope()))
                .append("sourceEvent", observation.sourceEvent()).append("merchant", observation.merchant())
                .append("sequence", observation.sequence()).append("eventTime", Date.from(observation.eventTime()))
                .append("inputCommitment", inputCommitment(observation))
                .append("previousCheckpointId", previous == null ? null : previous.id())
                .append("previousSequence", previous == null ? null : previous.sequence())
                .append("previousEventTime", previous == null ? null : Date.from(previous.eventTime()))
                .append("previousCommitment", previous == null ? null : previous.commitment())
                .append("membershipCommitment", commitment);
    }

    static MembershipCheckpoint decodeMembership(Document row) {
        try {
            Scope scope = decodeScope(row);
            String id = row.getString("_id");
            String event = row.getString("sourceEvent");
            String merchant = row.getString("merchant");
            long sequence = row.getLong("sequence");
            Instant eventTime = row.getDate("eventTime").toInstant();
            String input = row.getString("inputCommitment");
            String previousId = row.getString("previousCheckpointId");
            Long previousSequence = row.get("previousSequence", Long.class);
            Date previousDate = row.getDate("previousEventTime");
            Instant previousEventTime = previousDate == null ? null : previousDate.toInstant();
            String previousCommitment = row.getString("previousCommitment");
            String commitment = row.getString("membershipCommitment");
            boolean noPrevious = previousId == null && previousSequence == null && previousEventTime == null
                    && previousCommitment == null;
            boolean hasPrevious = previousId != null && previousSequence != null && previousEventTime != null
                    && previousCommitment != null;
            if (!noPrevious && !hasPrevious || id == null || !id.equals(eventId(scope, event))
                    || !scopeId(scope).equals(row.getString("scope")) || !reference(event) || !reference(merchant)
                    || !reference(input) || !reference(commitment) || sequence < 1
                    || previousSequence != null && (previousSequence < 1 || previousSequence >= sequence)
                    || previousCommitment != null && !reference(previousCommitment)
                    || previousId != null && previousId.length() > 265
                    || !membershipCommitment(scope, id, event, merchant, sequence, eventTime, input,
                    previousId, previousSequence, previousEventTime, previousCommitment).equals(commitment)) {
                throw failure("CORRUPT_MEMBERSHIP");
            }
            return new MembershipCheckpoint(scope, id, event, merchant, sequence, eventTime, input,
                    previousId, previousSequence, previousEventTime, previousCommitment, commitment);
        } catch (RuntimeException exception) {
            throw failure("CORRUPT_MEMBERSHIP");
        }
    }

    static Document membershipTipDocument(MembershipCheckpoint checkpoint, MembershipAnchor compactedCheckpoint,
                                          boolean compacted) {
        boolean anchorMatchesCheckpoint = compactedCheckpoint != null
                && compactedCheckpoint.id().equals(checkpoint.id())
                && compactedCheckpoint.sequence() == checkpoint.sequence()
                && compactedCheckpoint.eventTime().equals(checkpoint.eventTime())
                && compactedCheckpoint.commitment().equals(checkpoint.commitment());
        if (compacted && !anchorMatchesCheckpoint
                || !compacted && compactedCheckpoint != null && compactedCheckpoint.sequence() >= checkpoint.sequence()) {
            throw failure("MEMBERSHIP_TIP_MISMATCH");
        }
        return scopeDocument(checkpoint.scope()).append("_id", membershipTipId(checkpoint.scope(), checkpoint.merchant()))
                .append("scope", scopeId(checkpoint.scope())).append("merchant", checkpoint.merchant())
                .append("checkpointId", checkpoint.id()).append("sourceEvent", checkpoint.sourceEvent())
                .append("sequence", checkpoint.sequence()).append("eventTime", Date.from(checkpoint.eventTime()))
                .append("inputCommitment", checkpoint.inputCommitment())
                .append("previousCheckpointId", checkpoint.previousId())
                .append("previousSequence", checkpoint.previousSequence())
                .append("previousEventTime", checkpoint.previousEventTime() == null ? null : Date.from(checkpoint.previousEventTime()))
                .append("previousCommitment", checkpoint.previousCommitment())
                .append("membershipCommitment", checkpoint.commitment())
                .append("compactedCheckpoint", compactedCheckpoint == null ? null
                        : new Document("id", compactedCheckpoint.id()).append("sequence", compactedCheckpoint.sequence())
                        .append("eventTime", Date.from(compactedCheckpoint.eventTime()))
                        .append("commitment", compactedCheckpoint.commitment()))
                .append("compacted", compacted);
    }

    static MembershipTip decodeMembershipTip(Document row) {
        try {
            Scope scope = decodeScope(row);
            String merchant = row.getString("merchant");
            if (!reference(merchant) || !membershipTipId(scope, merchant).equals(row.getString("_id"))
                    || !scopeId(scope).equals(row.getString("scope"))) {
                throw failure("CORRUPT_MEMBERSHIP_TIP");
            }
            Document checkpoint = scopeDocument(scope).append("_id", row.getString("checkpointId"))
                    .append("scope", row.getString("scope")).append("sourceEvent", row.getString("sourceEvent"))
                    .append("merchant", merchant).append("sequence", row.getLong("sequence"))
                    .append("eventTime", row.getDate("eventTime")).append("inputCommitment", row.getString("inputCommitment"))
                    .append("previousCheckpointId", row.getString("previousCheckpointId"))
                    .append("previousSequence", row.get("previousSequence", Long.class))
                    .append("previousEventTime", row.getDate("previousEventTime"))
                    .append("previousCommitment", row.getString("previousCommitment"))
                    .append("membershipCommitment", row.getString("membershipCommitment"));
            MembershipCheckpoint decoded = decodeMembership(checkpoint);
            if (!row.containsKey("compactedCheckpoint")) { throw failure("CORRUPT_MEMBERSHIP_TIP"); }
            Document anchorDocument = row.get("compactedCheckpoint", Document.class);
            MembershipAnchor anchor = null;
            if (anchorDocument != null) {
                if (!anchorDocument.keySet().equals(Set.of("id", "sequence", "eventTime", "commitment"))) {
                    throw failure("CORRUPT_MEMBERSHIP_TIP");
                }
                String anchorId = anchorDocument.getString("id");
                Long anchorSequence = anchorDocument.get("sequence", Long.class);
                Date anchorEventTime = anchorDocument.getDate("eventTime");
                String anchorCommitment = anchorDocument.getString("commitment");
                if (!reference(anchorId) || anchorSequence == null || anchorSequence < 1
                        || anchorEventTime == null || !reference(anchorCommitment)
                        || anchorSequence > decoded.sequence()) { throw failure("CORRUPT_MEMBERSHIP_TIP"); }
                anchor = new MembershipAnchor(anchorId, anchorSequence, anchorEventTime.toInstant(), anchorCommitment);
            }
            Boolean compacted = row.getBoolean("compacted");
            boolean anchorMatchesCheckpoint = anchor != null && anchor.id().equals(decoded.id())
                    && anchor.sequence() == decoded.sequence() && anchor.eventTime().equals(decoded.eventTime())
                    && anchor.commitment().equals(decoded.commitment());
            if (!merchant.equals(decoded.merchant()) || compacted == null
                    || compacted && !anchorMatchesCheckpoint
                    || !compacted && anchor != null && anchor.sequence() >= decoded.sequence()) {
                throw failure("CORRUPT_MEMBERSHIP_TIP");
            }
            return new MembershipTip(decoded, anchor, compacted);
        } catch (RuntimeException exception) {
            throw failure("CORRUPT_MEMBERSHIP_TIP");
        }
    }

    static boolean membershipMatchesObservation(Document row, Observation observation) {
        try {
            MembershipCheckpoint member = decodeMembership(row);
            return member.scope().equals(observation.scope()) && member.id().equals(eventId(observation))
                    && member.sourceEvent().equals(observation.sourceEvent()) && member.merchant().equals(observation.merchant())
                    && member.sequence() == observation.sequence() && member.eventTime().equals(observation.eventTime())
                    && member.inputCommitment().equals(inputCommitment(observation));
        } catch (RuntimeException exception) { return false; }
    }

    static boolean membershipMatchesPredecessor(MembershipCheckpoint member, MembershipCheckpoint predecessor) {
        return predecessor != null && member.previousId() != null
                && member.previousId().equals(predecessor.id())
                && member.previousSequence().equals(predecessor.sequence())
                && member.previousEventTime().equals(predecessor.eventTime())
                && member.previousCommitment().equals(predecessor.commitment())
                && member.scope().equals(predecessor.scope()) && member.merchant().equals(predecessor.merchant())
                && predecessor.sequence() < member.sequence();
    }

    static boolean membershipMatchesAnchor(MembershipCheckpoint member, MembershipAnchor anchor) {
        return anchor != null && member.previousId() != null
                && member.previousId().equals(anchor.id())
                && member.previousSequence().equals(anchor.sequence())
                && member.previousEventTime().equals(anchor.eventTime())
                && member.previousCommitment().equals(anchor.commitment())
                && anchor.sequence() < member.sequence();
    }

    static boolean membershipHasNoPredecessor(MembershipCheckpoint member) {
        return member.previousId() == null && member.previousSequence() == null
                && member.previousEventTime() == null && member.previousCommitment() == null;
    }

    private static String membershipCommitment(Scope scope, String id, String event, String merchant, long sequence,
                                               Instant eventTime, String inputCommitment, String previousId,
                                               Long previousSequence, Instant previousEventTime, String previousCommitment) {
        return MerchantCommitment.sha256("MERCHANT_STORE_DIGEST_UNAVAILABLE", "merchant-membership/v3",
                scope.issuer(), scope.institution(), scope.source(), scope.epoch(), scope.identityVersion(), id, event,
                merchant, sequence, eventTime, inputCommitment, previousId, previousSequence, previousEventTime,
                previousCommitment);
    }

    private static boolean reference(String value) {
        return value != null && Pattern.matches("[0-9a-f]{64}", value);
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
