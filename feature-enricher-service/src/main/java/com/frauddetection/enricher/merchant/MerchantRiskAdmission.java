package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim;

/** Internal, source-neutral denial port. It exposes neither admitted evidence nor a history write path. */
public interface MerchantRiskAdmission {
    Denial admit(MerchantRiskEvidenceClaim claim);

    enum Denial {
        MERCHANT_RISK_ADMISSION_DISABLED_NO_TRUSTED_ISSUER
    }
}
