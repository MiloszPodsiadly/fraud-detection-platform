package com.frauddetection.alert.governance.modelpromotion;

import java.util.Objects;

public record ModelGovernanceScope(
        String institutionalScope,
        ModelGovernanceEnvironment environment
) {

    public static final String CURRENT_INSTITUTION = "CURRENT_INSTITUTION";

    public ModelGovernanceScope {
        if (!CURRENT_INSTITUTION.equals(institutionalScope)) {
            throw new IllegalArgumentException("Only the fixed current institutional scope is supported");
        }
        Objects.requireNonNull(environment, "environment is required");
    }
}
