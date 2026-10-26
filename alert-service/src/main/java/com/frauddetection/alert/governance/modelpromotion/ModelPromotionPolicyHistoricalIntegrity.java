package com.frauddetection.alert.governance.modelpromotion;

/** Cryptographic record inspection only. None of these statuses authorizes a new governance decision. */
record ModelPromotionPolicyHistoricalIntegrity(Status status,
        ModelPromotionPolicyAuthorityVerification.Reason reason) {

    enum Status {
        INTACT_ACTIVE_KEY,
        INTACT_RETIRED_KEY,
        INTACT_REVOKED_KEY,
        INVALID,
        UNAVAILABLE
    }
}
