package com.frauddetection.alert.governance.audit;

import java.util.List;

public class InvalidGovernanceAuditRequestException extends RuntimeException {

    private final List<String> details;

    public InvalidGovernanceAuditRequestException(List<String> details) {
        super("Invalid governance audit request.");
        this.details = List.copyOf(details);
    }

    public List<String> details() {
        return details;
    }
}
