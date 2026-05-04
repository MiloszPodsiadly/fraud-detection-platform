package com.frauddetection.alert.regulated;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class RegulatedMutationReplayResolver {

    private final RegulatedMutationReplayPolicyRegistry policyRegistry;

    public RegulatedMutationReplayResolver(RegulatedMutationReplayPolicyRegistry policyRegistry) {
        this.policyRegistry = policyRegistry;
    }

    public RegulatedMutationReplayDecision resolve(RegulatedMutationCommandDocument document, Instant now) {
        return policyRegistry.resolve(document, now);
    }
}
