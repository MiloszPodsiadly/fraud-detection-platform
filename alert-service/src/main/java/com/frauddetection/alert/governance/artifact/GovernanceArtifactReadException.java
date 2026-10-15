package com.frauddetection.alert.governance.artifact;

public class GovernanceArtifactReadException extends RuntimeException {

    public GovernanceArtifactReadException(String message) {
        super(message);
    }

    public GovernanceArtifactReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
