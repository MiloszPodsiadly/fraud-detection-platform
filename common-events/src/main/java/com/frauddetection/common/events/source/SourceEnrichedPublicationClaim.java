package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;

/** Fenced lease over one exact encrypted ENRICHED outbox fact. */
public record SourceEnrichedPublicationClaim(Status status, SourceLineage authority,
                                            TransactionEnrichedEvent event, String publicationId, String claimToken, int attempts) {
    public enum Status { CLAIMED, ALREADY_PUBLISHED, BUSY, EXHAUSTED, QUARANTINED, UNAVAILABLE }

    public SourceEnrichedPublicationClaim {
        if (status == null || authority == null || attempts < 0 || attempts > 8
                || (status == Status.CLAIMED) != (event != null && publicationId != null && claimToken != null)) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_CLAIM_INVALID");
        }
    }

    public static SourceEnrichedPublicationClaim unavailable(SourceLineage.State state) {
        return new SourceEnrichedPublicationClaim(Status.UNAVAILABLE, SourceLineage.unavailable(state), null, null, null, 0);
    }

    @Override public String toString() {
        return "SourceEnrichedPublicationClaim[" + status + ", authority=" + authority.state() + ", event=redacted]";
    }
}
