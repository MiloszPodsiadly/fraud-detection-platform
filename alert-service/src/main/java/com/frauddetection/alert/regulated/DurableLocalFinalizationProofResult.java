package com.frauddetection.alert.regulated;

public record DurableLocalFinalizationProofResult(boolean valid, String reasonCode) {

    public static DurableLocalFinalizationProofResult accepted() {
        return new DurableLocalFinalizationProofResult(true, null);
    }

    public static DurableLocalFinalizationProofResult invalid(String reasonCode) {
        return new DurableLocalFinalizationProofResult(false, reasonCode);
    }
}
