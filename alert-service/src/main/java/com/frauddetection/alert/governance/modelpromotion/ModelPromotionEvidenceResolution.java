package com.frauddetection.alert.governance.modelpromotion;

public record ModelPromotionEvidenceResolution(
        Status status,
        Reason reason,
        ModelPromotionEvidenceBundle evidenceBundle
) {

    public ModelPromotionEvidenceResolution {
        if (status == null || reason == null) {
            throw new IllegalArgumentException("status and reason are required");
        }
        if ((status == Status.AVAILABLE) != (evidenceBundle != null)) {
            throw new IllegalArgumentException("Only AVAILABLE resolution may contain an evidence bundle");
        }
        if (status == Status.AVAILABLE && reason != Reason.NONE) {
            throw new IllegalArgumentException("AVAILABLE resolution reason must be NONE");
        }
    }

    public static ModelPromotionEvidenceResolution available(ModelPromotionEvidenceBundle bundle) {
        return new ModelPromotionEvidenceResolution(Status.AVAILABLE, Reason.NONE, bundle);
    }

    public static ModelPromotionEvidenceResolution notFound(Reason reason) {
        return new ModelPromotionEvidenceResolution(Status.NOT_FOUND, reason, null);
    }

    public static ModelPromotionEvidenceResolution unavailable(Reason reason) {
        return new ModelPromotionEvidenceResolution(Status.UNAVAILABLE, reason, null);
    }

    public enum Status {
        AVAILABLE,
        NOT_FOUND,
        UNAVAILABLE
    }

    public enum Reason {
        NONE,
        MODEL_CARD_NOT_FOUND,
        MODEL_CARD_UNAVAILABLE,
        MODEL_CARD_EVALUATION_UNAVAILABLE,
        SHADOW_PERFORMANCE_NOT_FOUND,
        SHADOW_PERFORMANCE_UNAVAILABLE,
        EVIDENCE_IDENTITY_OR_WINDOW_MISMATCH,
        EVIDENCE_CANONICALIZATION_UNAVAILABLE
    }
}
