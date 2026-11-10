package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;

import java.util.List;

public interface SourceLineageAccess {
    SourceLineage raw(TransactionRawEvent event);
    SourceLineage enriched(TransactionEnrichedEvent event);
    SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output);

    /** Binds and durably stores the exact event before its Kafka publication. */
    default SourceEnrichedPublication prepareEnrichedPublication(TransactionRawEvent input, TransactionEnrichedEvent output) {
        return SourceEnrichedPublication.unavailable(SourceLineage.State.UNAVAILABLE);
    }

    /** Looks up the exact durable intent without requiring the caller to recalculate its features. */
    default SourceEnrichedPublicationLookup findEnrichedPublication(TransactionRawEvent input) {
        return SourceEnrichedPublicationLookup.unavailable(SourceLineage.State.UNAVAILABLE);
    }

    /** Claims the prepared exact event before a live Kafka send. */
    default SourceEnrichedPublicationClaim claimEnrichedPublication(TransactionRawEvent input, TransactionEnrichedEvent output) {
        return SourceEnrichedPublicationClaim.unavailable(SourceLineage.State.UNAVAILABLE);
    }

    /** Independently claims a bounded batch of durable events without consulting RAW outbox data. */
    default List<SourceEnrichedPublicationClaim> claimNextEnrichedPublications(int limit) { return List.of(); }

    /** Records a broker acknowledgement only for the matching unexpired lease token. */
    default SourceLineage markEnrichedPublished(SourceEnrichedPublicationClaim claim) {
        return SourceLineage.unavailable(SourceLineage.State.UNAVAILABLE);
    }

    /** Confirms Redis history application only for the matching publication lease and immutable event. */
    default SourceLineage markEnrichedHistoryApplied(SourceEnrichedPublicationClaim claim) {
        return SourceLineage.unavailable(SourceLineage.State.UNAVAILABLE);
    }

    /** Releases or backs off a failed Redis finalization without spending a Kafka publication attempt. */
    default void recordEnrichedHistoryFailure(SourceEnrichedPublicationClaim claim) { }

    /** Releases a claimed event with a bounded retry or terminal/quarantine state. */
    default void recordEnrichedPublishFailure(SourceEnrichedPublicationClaim claim) { }

    default EnrichedPublicationHealth enrichedPublicationHealth() {
        return new EnrichedPublicationHealth(0, 0, 0, 0, 0);
    }

    default EnrichedPublicationRedriveResult redriveEnrichedPublication(String publicationId, String reason) {
        return new EnrichedPublicationRedriveResult(EnrichedPublicationRedriveStatus.UNAVAILABLE, 0);
    }

    record EnrichedPublicationHealth(long pending, long leased, long retryExhausted,
                                     long quarantined, long oldestPendingAgeSeconds) { }
    enum EnrichedPublicationRedriveStatus { REQUEUED, UNAVAILABLE, AUTHORITY_DENIED, INVALID, EXHAUSTED }
    record EnrichedPublicationRedriveResult(EnrichedPublicationRedriveStatus status, int recoveryEpoch) {
        public EnrichedPublicationRedriveResult {
            if (status == null || recoveryEpoch < 0 || recoveryEpoch > 3) {
                throw new IllegalArgumentException("SOURCE_ENRICHED_REDRIVE_RESULT_INVALID");
            }
        }
        @Override public String toString() { return "SourceEnrichedRedriveResult[" + status + ", epoch=" + recoveryEpoch + "]"; }
    }

    default boolean isLegacyOnly() { return false; }

    /** Explicit deployment mode used only when attested ingress is not configured. */
    static SourceLineageAccess legacyOnly() {
        return new SourceLineageAccess() {
            @Override public SourceLineage raw(TransactionRawEvent event) { return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED); }
            @Override public SourceLineage enriched(TransactionEnrichedEvent event) { return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED); }
            @Override public SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output) {
                return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED);
            }
            @Override public boolean isLegacyOnly() { return true; }
        };
    }

    static SourceLineageAccess disabled() {
        return new SourceLineageAccess() {
            private SourceLineage absent() { return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED); }
            @Override public SourceLineage raw(TransactionRawEvent event) { return absent(); }
            @Override public SourceLineage enriched(TransactionEnrichedEvent event) { return absent(); }
            @Override public SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output) { return absent(); }
        };
    }
}
