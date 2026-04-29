package com.frauddetection.trustauthority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TrustAuthorityAuditIntegrityVerifier {

    private TrustAuthorityAuditIntegrityVerifier() {
    }

    static TrustAuthorityAuditIntegrityResponse verify(List<TrustAuthorityAuditEvent> events) {
        return verify(events, TrustAuthorityAuditIntegrityMode.FULL_CHAIN);
    }

    static TrustAuthorityAuditIntegrityResponse verify(List<TrustAuthorityAuditEvent> events, TrustAuthorityAuditIntegrityMode mode) {
        List<TrustAuthorityAuditEvent> ordered = events.stream()
                .sorted(Comparator.comparing(event -> event.chainPosition() == null ? Long.MAX_VALUE : event.chainPosition()))
                .toList();
        List<TrustAuthorityAuditIntegrityViolation> violations = new ArrayList<>();
        Long previousPosition = null;
        String previousHash = null;
        TrustAuthorityAuditEvent latest = null;
        TrustAuthorityAuditEvent first = ordered.isEmpty() ? null : ordered.getFirst();
        boolean boundaryOutsideWindow = mode == TrustAuthorityAuditIntegrityMode.WINDOW
                && first != null
                && first.chainPosition() != null
                && first.chainPosition() > 1
                && first.previousEventHash() != null;
        String boundaryPreviousHash = boundaryOutsideWindow ? first.previousEventHash() : null;
        for (TrustAuthorityAuditEvent event : ordered) {
            latest = event;
            if (event.chainPosition() == null) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("CHAIN_POSITION_MISSING", null, "Audit event chain position is missing."));
                continue;
            }
            if (previousPosition == null
                    && mode == TrustAuthorityAuditIntegrityMode.FULL_CHAIN
                    && event.chainPosition() != 1L) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("CHAIN_POSITION_GAP", event.chainPosition(), "Full chain verification did not start at genesis."));
            }
            if (previousPosition == null
                    && mode == TrustAuthorityAuditIntegrityMode.WINDOW
                    && event.chainPosition() > 1L
                    && event.previousEventHash() == null) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("PREVIOUS_EVENT_HASH_MISMATCH", event.chainPosition(), "Window boundary predecessor hash is missing."));
            }
            if (previousPosition != null && event.chainPosition() != previousPosition + 1) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("CHAIN_POSITION_GAP", event.chainPosition(), "Audit chain position is not contiguous."));
            }
            boolean firstWindowEventWithExternalPredecessor = mode == TrustAuthorityAuditIntegrityMode.WINDOW
                    && previousPosition == null
                    && boundaryOutsideWindow;
            if (!firstWindowEventWithExternalPredecessor && !same(previousHash, event.previousEventHash())) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("PREVIOUS_EVENT_HASH_MISMATCH", event.chainPosition(), "Audit previous hash does not match predecessor."));
            }
            String expectedHash = TrustAuthorityAuditHasher.hash(event, event.previousEventHash(), event.chainPosition());
            if (!same(expectedHash, event.eventHash())) {
                violations.add(new TrustAuthorityAuditIntegrityViolation("EVENT_HASH_MISMATCH", event.chainPosition(), "Audit event hash does not match canonical event content."));
            }
            previousPosition = event.chainPosition();
            previousHash = event.eventHash();
        }
        Long start = ordered.isEmpty() ? null : ordered.getFirst().chainPosition();
        Long end = latest == null ? null : latest.chainPosition();
        String status = violations.isEmpty()
                ? (boundaryOutsideWindow ? "PARTIAL" : "VALID")
                : "INVALID";
        String reasonCode = violations.isEmpty()
                ? (boundaryOutsideWindow ? "BOUNDARY_PREDECESSOR_OUTSIDE_WINDOW" : null)
                : "AUDIT_CHAIN_INVALID";
        return new TrustAuthorityAuditIntegrityResponse(
                status,
                ordered.size(),
                mode.name(),
                end,
                latest == null ? null : latest.eventHash(),
                start,
                end,
                boundaryPreviousHash,
                reasonCode,
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
