package com.frauddetection.alert.trust;

public class TrustIncidentMaterializationException extends RuntimeException {

    private final TrustIncidentMaterializationResponse response;

    public TrustIncidentMaterializationException(TrustIncidentMaterializationResponse response, Throwable cause) {
        super(response.failureReason(), cause);
        this.response = response;
    }

    public TrustIncidentMaterializationResponse response() {
        return response;
    }
}
