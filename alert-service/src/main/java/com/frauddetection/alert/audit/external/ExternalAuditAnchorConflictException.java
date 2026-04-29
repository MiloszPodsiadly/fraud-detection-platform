package com.frauddetection.alert.audit.external;

import java.util.List;

class ExternalAuditAnchorConflictException extends ExternalAuditAnchorSinkException {

    private final List<String> conflictingHashes;
    private final List<String> witnessSources;

    ExternalAuditAnchorConflictException(List<String> conflictingHashes, List<String> witnessSources) {
        super("CONFLICT", "External anchor witnesses contain incompatible truths for the same chain position.");
        this.conflictingHashes = conflictingHashes == null ? List.of() : List.copyOf(conflictingHashes);
        this.witnessSources = witnessSources == null ? List.of() : List.copyOf(witnessSources);
    }

    List<String> conflictingHashes() {
        return conflictingHashes;
    }

    List<String> witnessSources() {
        return witnessSources;
    }
}
