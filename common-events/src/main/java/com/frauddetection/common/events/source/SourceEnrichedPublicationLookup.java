package com.frauddetection.common.events.source;

/** Result of checking durable ENRICHED intent and any exact terminal RAW disposition before recalculation. */
public record SourceEnrichedPublicationLookup(SourceLineage authority, SourceEnrichedPublication publication, Outcome outcome) {
    public enum Outcome { NOT_FOUND, FOUND, LATE_REJECTED, UNAVAILABLE }

    public SourceEnrichedPublicationLookup {
        if (authority == null || outcome == null
                || outcome != Outcome.UNAVAILABLE && authority.state() != SourceLineage.State.VERIFIED
                || (outcome == Outcome.FOUND) != (publication != null)
                || publication != null && (authority.state() != SourceLineage.State.VERIFIED
                || publication.authority().state() != SourceLineage.State.VERIFIED
                || !authority.receiptId().equals(publication.authority().receiptId()))) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_LOOKUP_INVALID");
        }
    }

    public static SourceEnrichedPublicationLookup notFound(SourceLineage authority) {
        if (authority == null || authority.state() != SourceLineage.State.VERIFIED) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_LOOKUP_INVALID");
        }
        return new SourceEnrichedPublicationLookup(authority, null, Outcome.NOT_FOUND);
    }

    public static SourceEnrichedPublicationLookup found(SourceEnrichedPublication publication) {
        if (publication == null || publication.authority().state() != SourceLineage.State.VERIFIED
                || publication.event() == null) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_LOOKUP_INVALID");
        }
        return new SourceEnrichedPublicationLookup(publication.authority(), publication, Outcome.FOUND);
    }

    public static SourceEnrichedPublicationLookup lateRejected(SourceLineage authority) {
        if (authority == null || authority.state() != SourceLineage.State.VERIFIED) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_LOOKUP_INVALID");
        }
        return new SourceEnrichedPublicationLookup(authority, null, Outcome.LATE_REJECTED);
    }

    public static SourceEnrichedPublicationLookup unavailable(SourceLineage.State state) {
        return new SourceEnrichedPublicationLookup(SourceLineage.unavailable(state), null, Outcome.UNAVAILABLE);
    }

    @Override public String toString() {
        return "SourceEnrichedPublicationLookup[authority=" + authority.state() + ", outcome=" + outcome
                + ", prepared=" + (publication != null) + ", publication=redacted]";
    }
}
