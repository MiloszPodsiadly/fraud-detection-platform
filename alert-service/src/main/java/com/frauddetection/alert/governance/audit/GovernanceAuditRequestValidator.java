package com.frauddetection.alert.governance.audit;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GovernanceAuditRequestValidator {

    public static final int MAX_NOTE_LENGTH = 500;

    public GovernanceAuditCommand validate(GovernanceAuditRequest request) {
        if (request == null) {
            throw new InvalidGovernanceAuditRequestException(List.of("request: body is required"));
        }

        GovernanceAuditDecision decision = GovernanceAuditDecision.parse(request.decision());
        String note = normalizeNote(request.note());
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new InvalidGovernanceAuditRequestException(List.of("note: size must be at most " + MAX_NOTE_LENGTH));
        }
        return new GovernanceAuditCommand(decision, note);
    }

    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
