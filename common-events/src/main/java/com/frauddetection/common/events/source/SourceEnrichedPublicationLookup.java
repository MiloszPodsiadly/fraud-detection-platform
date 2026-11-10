package com.frauddetection.common.events.source;

/** Result of checking durable ENRICHED intent before recalculating a duplicate RAW event. */
public record SourceEnrichedPublicationLookup(SourceLineage authority, SourceEnrichedPublication publication) {
    public SourceEnrichedPublicationLookup {
        if (authority == null || publication != null && (authority.state() != SourceLineage.State.VERIFIED
                || publication.authority().state() != SourceLineage.State.VERIFIED
                || !authority.receiptId().equals(publication.authority().receiptId()))) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_LOOKUP_INVALID");
        }
    }

    public static SourceEnrichedPublicationLookup notFound(SourceLineage authority) {
        if (authority == null || authority.state() != SourceLineage.State.VERIFIED) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_LOOKUP_INVALID");
        }
        return new SourceEnrichedPublicationLookup(authority, null);
    }

    public static SourceEnrichedPublicationLookup found(SourceEnrichedPublication publication) {
        if (publication == null || publication.authority().state() != SourceLineage.State.VERIFIED
                || publication.event() == null) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_LOOKUP_INVALID");
        }
        return new SourceEnrichedPublicationLookup(publication.authority(), publication);
    }

    public static SourceEnrichedPublicationLookup unavailable(SourceLineage.State state) {
        return new SourceEnrichedPublicationLookup(SourceLineage.unavailable(state), null);
    }

    @Override public String toString() {
        return "SourceEnrichedPublicationLookup[authority=" + authority.state()
                + ", prepared=" + (publication != null) + ", publication=redacted]";
    }
}
