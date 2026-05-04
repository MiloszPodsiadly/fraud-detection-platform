package com.frauddetection.alert.regulated;

import java.time.Instant;

public interface RegulatedMutationReplayPolicy {

    RegulatedMutationModelVersion modelVersion();

    RegulatedMutationReplayDecision resolve(RegulatedMutationCommandDocument document, Instant now);
}
