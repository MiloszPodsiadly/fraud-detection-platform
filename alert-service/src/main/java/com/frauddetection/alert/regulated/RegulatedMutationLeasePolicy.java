package com.frauddetection.alert.regulated;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class RegulatedMutationLeasePolicy {

    public boolean isActiveProcessingLease(RegulatedMutationCommandDocument document, Instant now) {
        return document.getExecutionStatus() == RegulatedMutationExecutionStatus.PROCESSING
                && !leaseExpired(document, now);
    }

    public boolean isExpiredProcessingLease(RegulatedMutationCommandDocument document, Instant now) {
        return document.getExecutionStatus() == RegulatedMutationExecutionStatus.PROCESSING
                && leaseExpired(document, now);
    }

    public boolean leaseExpired(RegulatedMutationCommandDocument document, Instant now) {
        return document.getLeaseExpiresAt() == null || !document.getLeaseExpiresAt().isAfter(now);
    }
}
