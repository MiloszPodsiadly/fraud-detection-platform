package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatedMutationLeasePolicyTest {

    private final RegulatedMutationLeasePolicy policy = new RegulatedMutationLeasePolicy();
    private final Instant now = Instant.parse("2026-05-04T12:00:00Z");

    @Test
    void nullLeaseExpirationIsExpired() {
        RegulatedMutationCommandDocument document = processing(null);

        assertThat(policy.leaseExpired(document, now)).isTrue();
        assertThat(policy.isExpiredProcessingLease(document, now)).isTrue();
    }

    @Test
    void pastLeaseExpirationIsExpired() {
        RegulatedMutationCommandDocument document = processing(now.minusSeconds(1));

        assertThat(policy.leaseExpired(document, now)).isTrue();
        assertThat(policy.isExpiredProcessingLease(document, now)).isTrue();
    }

    @Test
    void exactNowLeaseExpirationIsExpired() {
        RegulatedMutationCommandDocument document = processing(now);

        assertThat(policy.leaseExpired(document, now)).isTrue();
        assertThat(policy.isExpiredProcessingLease(document, now)).isTrue();
    }

    @Test
    void futureLeaseExpirationIsActive() {
        RegulatedMutationCommandDocument document = processing(now.plusSeconds(1));

        assertThat(policy.leaseExpired(document, now)).isFalse();
        assertThat(policy.isActiveProcessingLease(document, now)).isTrue();
    }

    @Test
    void onlyProcessingStatusIsActiveProcessingLease() {
        RegulatedMutationCommandDocument document = processing(now.plusSeconds(1));
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);

        assertThat(policy.isActiveProcessingLease(document, now)).isFalse();
        assertThat(policy.isExpiredProcessingLease(document, now)).isFalse();
    }

    @Test
    void completedWithFutureLeaseIsNotActiveProcessingLease() {
        RegulatedMutationCommandDocument document = processing(now.plusSeconds(1));
        document.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);

        assertThat(policy.isActiveProcessingLease(document, now)).isFalse();
    }

    private RegulatedMutationCommandDocument processing(Instant leaseExpiresAt) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseExpiresAt(leaseExpiresAt);
        return document;
    }
}
