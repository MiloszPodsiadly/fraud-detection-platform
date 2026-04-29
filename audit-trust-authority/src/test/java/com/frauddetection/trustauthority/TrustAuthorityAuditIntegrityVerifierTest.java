package com.frauddetection.trustauthority;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrustAuthorityAuditIntegrityVerifierTest {

    @Test
    void shouldValidateContiguousAuditHashChain() {
        TrustAuthorityAuditEvent first = chained(event("event-1", "SIGN"), null, 1L);
        TrustAuthorityAuditEvent second = chained(event("event-2", "VERIFY"), first.eventHash(), 2L);

        TrustAuthorityAuditIntegrityResponse response = TrustAuthorityAuditIntegrityVerifier.verify(List.of(second, first));

        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.mode()).isEqualTo("FULL_CHAIN");
        assertThat(response.checked()).isEqualTo(2);
        assertThat(response.latestChainPosition()).isEqualTo(2L);
        assertThat(response.latestEventHash()).isEqualTo(second.eventHash());
        assertThat(response.capabilityLevel()).isEqualTo(TrustAuthorityCapabilityLevel.INTERNAL_CRYPTOGRAPHIC_TRUST);
        assertThat(response.tamperDetected()).isFalse();
        assertThat(response.integrityConfidence()).isEqualTo(TrustAuthorityIntegrityConfidence.FULL_CHAIN_VERIFIED);
        assertThat(response.violations()).isEmpty();
    }

    @Test
    void shouldReportPartialForValidBoundedWindowWithExternalPredecessor() {
        TrustAuthorityAuditEvent first = chained(event("event-1", "SIGN"), null, 1L);
        TrustAuthorityAuditEvent second = chained(event("event-2", "VERIFY"), first.eventHash(), 2L);
        TrustAuthorityAuditEvent third = chained(event("event-3", "VERIFY"), second.eventHash(), 3L);

        TrustAuthorityAuditIntegrityResponse response = TrustAuthorityAuditIntegrityVerifier.verify(
                List.of(third, second),
                TrustAuthorityAuditIntegrityMode.WINDOW
        );

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.reasonCode()).isEqualTo("BOUNDARY_PREDECESSOR_OUTSIDE_WINDOW");
        assertThat(response.tamperDetected()).isFalse();
        assertThat(response.integrityConfidence()).isEqualTo(TrustAuthorityIntegrityConfidence.PARTIAL_BOUNDARY);
        assertThat(response.windowStartChainPosition()).isEqualTo(2L);
        assertThat(response.windowEndChainPosition()).isEqualTo(3L);
        assertThat(response.boundaryPreviousEventHash()).isEqualTo(first.eventHash());
        assertThat(response.violations()).isEmpty();
    }

    @Test
    void shouldDetectInternalPreviousHashMismatchInsideWindow() {
        TrustAuthorityAuditEvent first = chained(event("event-1", "SIGN"), null, 1L);
        TrustAuthorityAuditEvent second = chained(event("event-2", "VERIFY"), first.eventHash(), 2L);
        TrustAuthorityAuditEvent tamperedThird = chained(event("event-3", "VERIFY"), "wrong", 3L);

        TrustAuthorityAuditIntegrityResponse response = TrustAuthorityAuditIntegrityVerifier.verify(
                List.of(second, tamperedThird),
                TrustAuthorityAuditIntegrityMode.WINDOW
        );

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.tamperDetected()).isTrue();
        assertThat(response.violations()).extracting(TrustAuthorityAuditIntegrityViolation::reasonCode)
                .contains("PREVIOUS_EVENT_HASH_MISMATCH");
    }

    @Test
    void shouldDetectTamperedAuditEventHash() {
        TrustAuthorityAuditEvent first = chained(event("event-1", "SIGN"), null, 1L);
        TrustAuthorityAuditEvent tampered = chained(event("event-2", "VERIFY"), first.eventHash(), 2L)
                .withChain(first.eventHash(), "tampered", 2L);

        TrustAuthorityAuditIntegrityResponse response = TrustAuthorityAuditIntegrityVerifier.verify(List.of(first, tampered));

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reasonCode()).isEqualTo("AUDIT_CHAIN_INVALID");
        assertThat(response.violations()).extracting(TrustAuthorityAuditIntegrityViolation::reasonCode)
                .contains("EVENT_HASH_MISMATCH");
    }

    @Test
    void shouldDetectAuditChainGaps() {
        TrustAuthorityAuditEvent first = chained(event("event-1", "SIGN"), null, 1L);
        TrustAuthorityAuditEvent third = chained(event("event-3", "VERIFY"), first.eventHash(), 3L);

        TrustAuthorityAuditIntegrityResponse response = TrustAuthorityAuditIntegrityVerifier.verify(List.of(first, third));

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.violations()).extracting(TrustAuthorityAuditIntegrityViolation::reasonCode)
                .contains("CHAIN_POSITION_GAP");
    }

    @Test
    void shouldRejectUnknownAuditEventSchemaVersion() {
        TrustAuthorityAuditEvent event = new TrustAuthorityAuditEvent(
                99,
                "event-1",
                "SIGN",
                "alert-service@local",
                "alert-service",
                "request-1",
                "AUDIT_ANCHOR",
                "payload-hash",
                "key-1",
                "SUCCESS",
                null,
                Instant.parse("2026-04-27T10:00:00Z"),
                null,
                null,
                null
        );
        TrustAuthorityAuditEvent chained = event.withChain(null, TrustAuthorityAuditHasher.hash(event, null, 1L), 1L);

        TrustAuthorityAuditIntegrityResponse response = TrustAuthorityAuditIntegrityVerifier.verify(List.of(chained));

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.tamperDetected()).isTrue();
        assertThat(response.violations()).extracting(TrustAuthorityAuditIntegrityViolation::reasonCode)
                .contains("EVENT_SCHEMA_VERSION_UNSUPPORTED");
    }

    private TrustAuthorityAuditEvent chained(TrustAuthorityAuditEvent event, String previousHash, long position) {
        return event.withChain(previousHash, TrustAuthorityAuditHasher.hash(event, previousHash, position), position);
    }

    private TrustAuthorityAuditEvent event(String eventId, String action) {
        return new TrustAuthorityAuditEvent(
                TrustAuthorityAuditEvent.CURRENT_SCHEMA_VERSION,
                eventId,
                action,
                "alert-service@local",
                "alert-service",
                "request-" + eventId,
                "AUDIT_ANCHOR",
                "payload-hash",
                "key-1",
                "SUCCESS",
                null,
                Instant.parse("2026-04-27T10:00:00Z"),
                null,
                null,
                null
        );
    }
}
