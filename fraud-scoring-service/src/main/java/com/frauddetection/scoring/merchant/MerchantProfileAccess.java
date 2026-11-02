package com.frauddetection.scoring.merchant;

import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.scoring.context.ScoringContext;

/** Read-only access seam. No production provider exists; retrieval and structural binding never establish authority. */
@FunctionalInterface
interface MerchantProfileAccess extends MerchantRiskSignalPolicy.ProfileAdmission {
    ProfileRead read(ScoringContext context) throws Exception;

    /** Requires independent authority for this scoring occurrence, not equality with caller-supplied transaction fields. */
    default void requireOccurrence(ScoringContext context, ScopeClaim scope, OccurrenceClaim occurrence) {
        throw new SecurityException("MERCHANT_NOT_ADMITTED");
    }

    @Override
    default void requireProfile(ScopeClaim scope, OccurrenceClaim occurrence, MerchantProfile profile) {
        throw new SecurityException("MERCHANT_NOT_ADMITTED");
    }

    /** Unadmitted immutable read description, not a verification receipt. */
    record ProfileRead(ScopeClaim scope, OccurrenceClaim occurrence, MerchantProfile profile) {
        public ProfileRead {
            if (scope == null || occurrence == null || profile == null) {
                throw new IllegalArgumentException("MERCHANT_PROFILE_READ_SHAPE");
            }
        }
        @Override public String toString() { return "MerchantProfileRead[redacted]"; }
    }
}
