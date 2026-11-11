package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;

/** Private durable publication state; it is never added to the public event contract. */
public record SourceEnrichedPublication(SourceLineage authority, TransactionEnrichedEvent event,
                                       boolean published, boolean historyApplied) {
    public SourceEnrichedPublication {
        if (authority == null || (authority.state() == SourceLineage.State.VERIFIED) != (event != null)
                || (published && event == null) || (historyApplied && event == null)) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_STATE_INVALID");
        }
    }

    public static SourceEnrichedPublication unavailable(SourceLineage.State state) {
        return new SourceEnrichedPublication(SourceLineage.unavailable(state), null, false, false);
    }

    @Override public String toString() {
        return "SourceEnrichedPublication[authority=" + authority.state() + ", event=redacted, published=" + published
                + ", historyApplied=" + historyApplied + "]";
    }
}
