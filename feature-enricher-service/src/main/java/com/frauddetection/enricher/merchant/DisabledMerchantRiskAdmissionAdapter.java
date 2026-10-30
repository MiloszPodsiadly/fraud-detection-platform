package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim;

/** Denies every claim without reading identity fields, accessing storage, or creating an admission proof. */
public final class DisabledMerchantRiskAdmissionAdapter implements MerchantRiskAdmission {
    @Override
    public Denial admit(MerchantRiskEvidenceClaim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("MERCHANT_RISK_ADMISSION_CLAIM_REQUIRED");
        }
        return Denial.MERCHANT_RISK_ADMISSION_DISABLED_NO_TRUSTED_ISSUER;
    }
}
