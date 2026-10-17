package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class ModelPromotionLocalAuditFactory {

    public ModelPromotionDecisionDocument.LocalAuditEvidence accepted(
            String action,
            ModelPromotionOperationContext context,
            Instant committedAt
    ) {
        return new ModelPromotionDecisionDocument.LocalAuditEvidence(
                UUID.randomUUID().toString(),
                action,
                "SUCCEEDED",
                context.actorId(),
                context.authorizationFingerprint(),
                committedAt
        );
    }
}
