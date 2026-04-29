package com.frauddetection.trustauthority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TrustAuthorityAuditIntegrityVerifier {

    private TrustAuthorityAuditIntegrityVerifier() {
    }

    static TrustAuthorityAuditIntegrityResponse verify(List<TrustAuthorityAuditEvent> events) {
        List<TrustAuthorityAuditEvent> ordered = events.stream()
                .sorted(Comparator.comparing(event -> event.chainPosition() == null ? Long.MAX_VALUE : event.chainPosition()))
                .toList();
        List<TrustAuthorityAuditIntegrityViolation> violations = new ArrayList<>();
        Long previousPosition = null;
        String previousHash = null;
        TrustAuthorityAuditEvent latest = null;
        for (TrustAuthorityAuditEvent event : ordered) {
            latest = event;
            if (event.chainPosition() == null) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("CHAIN_POSITION_MISSING", null, "Audit event chain position is missing."));
                continue;
            }
            if (previousPosition != null && event.chainPosition() != previousPosition + 1) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("CHAIN_POSITION_GAP", event.chainPosition(), "Audit chain position is not contiguous."));
            }
            if (!same(previousHash, event.previousEventHash())) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("PREVIOUS_EVENT_HASH_MISMATCH", event.chainPosition(), "Audit previous hash does not match predecessor."));
            }
            String expectedHash = TrustAuthorityAuditHasher.hash(event, event.previousEventHash(), event.chainPosition());
            if (!same(expectedHash, event.eventHash())) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("EVENT_HASH_MISMATCH", event.chainPosition(), "Audit event hash does not match canonical event content."));
            }
            previousPosition = event.chainPosition();
            previousHash = event.eventHash();
        }
        return new TrustAuthorityAuditIntegrityResponse(
                violations.isEmpty() ? "VALID" : "INVALID",
                ordered.size(),
                latest == null ? null : latest.chainPosition(),
                latest == null ? null : latest.eventHash(),
                violations.isEmpty() ? null : "AUDIT_CHAIN_INVALID",
                violations
        );
    }

    private static boolean same(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
