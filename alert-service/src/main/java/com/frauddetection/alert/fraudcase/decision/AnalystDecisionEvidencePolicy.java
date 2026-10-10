package com.frauddetection.alert.fraudcase.decision;

import com.frauddetection.alert.persistence.AlertDocument;

public final class AnalystDecisionEvidencePolicy {

    private AnalystDecisionEvidencePolicy() {
    }

    public static boolean ownsHistoricalEvidence(AlertDocument alert) {
        return alert != null && (alert.getAnalystDecision() != null || alert.getDecidedAt() != null);
    }
}
