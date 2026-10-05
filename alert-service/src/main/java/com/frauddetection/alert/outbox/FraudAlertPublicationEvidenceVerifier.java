package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;

public interface FraudAlertPublicationEvidenceVerifier {

    ResolutionEvidenceReference verifyPublished(
            FraudAlertOutboxRecord authoritativeRecord,
            ResolutionEvidenceReference claimedEvidence
    );
}
