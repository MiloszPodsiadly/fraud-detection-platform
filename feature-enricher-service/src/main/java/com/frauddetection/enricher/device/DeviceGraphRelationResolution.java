package com.frauddetection.enricher.device;

import com.frauddetection.enricher.graph.GraphReadReason;

import java.util.Objects;

/** Owner-domain result for one fresh Device graph relation check. */
public final class DeviceGraphRelationResolution {
    public enum State { VERIFIED, PENDING, UNAVAILABLE, DENIED, CONFLICT }

    private final State state;
    private final GraphReadReason reason;
    private final VerifiedDeviceGraphRelation relation;

    private DeviceGraphRelationResolution(State state, GraphReadReason reason,
                                          VerifiedDeviceGraphRelation relation) {
        this.state = Objects.requireNonNull(state, "DEVICE_GRAPH_STATE_REQUIRED");
        this.reason = Objects.requireNonNull(reason, "DEVICE_GRAPH_REASON_REQUIRED");
        this.relation = relation;
        if (state == State.VERIFIED
                ? relation == null || reason != GraphReadReason.NONE
                    || !relation.validUntil().isAfter(relation.reference().asOf())
                    || relation.validUntil().isAfter(relation.reference().expiresAt())
                : relation != null || reason == GraphReadReason.NONE) {
            throw new IllegalArgumentException("DEVICE_GRAPH_RESOLUTION_INVALID");
        }
    }

    public static DeviceGraphRelationResolution verified(VerifiedDeviceGraphRelation relation) {
        return new DeviceGraphRelationResolution(State.VERIFIED, GraphReadReason.NONE,
                Objects.requireNonNull(relation, "DEVICE_GRAPH_RELATION_REQUIRED"));
    }

    public static DeviceGraphRelationResolution pending(GraphReadReason reason) {
        return new DeviceGraphRelationResolution(State.PENDING, reason, null);
    }

    public static DeviceGraphRelationResolution unavailable(GraphReadReason reason) {
        return new DeviceGraphRelationResolution(State.UNAVAILABLE, reason, null);
    }

    public static DeviceGraphRelationResolution denied(GraphReadReason reason) {
        return new DeviceGraphRelationResolution(State.DENIED, reason, null);
    }

    public static DeviceGraphRelationResolution conflict() {
        return new DeviceGraphRelationResolution(State.CONFLICT, GraphReadReason.CONFLICTING_EVIDENCE, null);
    }

    public State state() { return state; }
    public GraphReadReason reason() { return reason; }
    public java.util.Optional<VerifiedDeviceGraphRelation> relation() {
        return java.util.Optional.ofNullable(relation);
    }

    @Override public String toString() {
        return "DeviceGraphRelationResolution[" + state + "," + reason + ",redacted]";
    }
}
