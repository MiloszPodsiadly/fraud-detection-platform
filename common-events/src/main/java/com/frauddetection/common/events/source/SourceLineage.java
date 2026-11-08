package com.frauddetection.common.events.source;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;

import java.util.Map;
import java.util.Objects;

/** Private processing context, never an event/API field or permission to read domain history. */
@JsonIgnoreType
public final class SourceLineage {
    public enum State { VERIFIED, UNVERIFIED, UNAVAILABLE, CONFLICT, REVOKED }
    private final State state;
    private final String receiptId;
    private final SourceScope source;
    private final String eventCommitment;
    private static final SourceLineage UNVERIFIED_VALUE = new SourceLineage(State.UNVERIFIED, null, null, null);
    private static final SourceLineage UNAVAILABLE_VALUE = new SourceLineage(State.UNAVAILABLE, null, null, null);
    private static final SourceLineage CONFLICT_VALUE = new SourceLineage(State.CONFLICT, null, null, null);
    private static final SourceLineage REVOKED_VALUE = new SourceLineage(State.REVOKED, null, null, null);
    private SourceLineage(State state, String receiptId, SourceScope source, String eventCommitment) {
        this.state = state; this.receiptId = receiptId; this.source = source; this.eventCommitment = eventCommitment;
    }
    static SourceLineage verified(String receiptId, SourceScope source, String eventCommitment) {
        return new SourceLineage(State.VERIFIED, receiptId, source, eventCommitment);
    }
    public static SourceLineage unavailable(State state) {
        if (state == null || state == State.VERIFIED) { throw new IllegalArgumentException("SOURCE_LINEAGE_STATE_INVALID"); }
        return switch (state) {
            case UNVERIFIED -> UNVERIFIED_VALUE;
            case UNAVAILABLE -> UNAVAILABLE_VALUE;
            case CONFLICT -> CONFLICT_VALUE;
            case REVOKED -> REVOKED_VALUE;
            case VERIFIED -> throw new IllegalArgumentException("SOURCE_LINEAGE_STATE_INVALID");
        };
    }
    public State state() { return state; }
    public String receiptId() { return receiptId; }
    public SourceScope source() { return source; }
    public String eventCommitment() { return eventCommitment; }
    /** Narrows existing evidence to its exact input; never grants or refreshes source authority. */
    public SourceLineage forEnriched(TransactionEnrichedEvent event, Map<String, Object> features) {
        if (state != State.VERIFIED) { return this; }
        try {
            return event != null && Objects.equals(features, event.featureSnapshot())
                    && eventCommitment.equals(SourceEventCommitment.enriched(event)) ? this : CONFLICT_VALUE;
        } catch (RuntimeException unavailable) { return UNAVAILABLE_VALUE; }
    }
    @Override public String toString() { return "SourceLineage[" + state + "]"; }
}
