package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.regex.Pattern;

/** Durable actor identity for model-promotion authorization and audit records. */
public record ModelPromotionActorIdentity(
        @Field("schema_version") int schemaVersion,
        @Field("kind") Kind kind,
        @Field("actor_id") String actorId,
        @Field("issuer") String issuer,
        @Field("subject") String subject
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    private static final Pattern LOCAL_ACTOR = Pattern.compile("^local:[0-9a-f]{64}$");

    public ModelPromotionActorIdentity {
        if (schemaVersion != CURRENT_SCHEMA_VERSION || kind == null || actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("A versioned model-promotion actor identity is required");
        }
        if (kind == Kind.INSTITUTIONAL) {
            if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()
                    || !actorId.equals(PolicyGovernanceActor.identityId(issuer, subject))) {
                throw new IllegalArgumentException("Institutional actor identity must match its issuer and subject");
            }
        } else if (issuer != null || subject != null || !LOCAL_ACTOR.matcher(actorId).matches()) {
            throw new IllegalArgumentException("Local actor identity must use the isolated local namespace");
        }
    }

    public static ModelPromotionActorIdentity fromPrincipal(AnalystPrincipal principal) {
        if (principal == null) {
            throw unsupportedPrincipal();
        }
        String institutional = principal.institutionalActorId();
        int separator = institutional == null ? -1 : institutional.lastIndexOf('#');
        if (separator > 0 && separator < institutional.length() - 1) {
            String issuer = institutional.substring(0, separator);
            String subject = institutional.substring(separator + 1);
            String actorId = PolicyGovernanceActor.identityId(issuer, subject);
            if (principal.readAuditActorId() != null && !actorId.equals(principal.readAuditActorId())) {
                throw unsupportedPrincipal();
            }
            return institutional(issuer, subject);
        }
        String localActorId = principal.readAuditActorId();
        if (institutional == null && localActorId != null && LOCAL_ACTOR.matcher(localActorId).matches()) {
            return localDemo(localActorId);
        }
        throw unsupportedPrincipal();
    }

    public static ModelPromotionActorIdentity institutional(String issuer, String subject) {
        return new ModelPromotionActorIdentity(CURRENT_SCHEMA_VERSION, Kind.INSTITUTIONAL,
                PolicyGovernanceActor.identityId(issuer, subject), issuer, subject);
    }

    public static ModelPromotionActorIdentity localDemo(String actorId) {
        return new ModelPromotionActorIdentity(CURRENT_SCHEMA_VERSION, Kind.LOCAL_DEMO, actorId, null, null);
    }

    public boolean isInstitutional() {
        return kind == Kind.INSTITUTIONAL;
    }

    private static ModelPromotionWorkflowBlockedException unsupportedPrincipal() {
        return new ModelPromotionWorkflowBlockedException(
                ModelPromotionWorkflowBlockedException.Reason.UNSUPPORTED_PRINCIPAL,
                "A verified institutional or isolated local actor identity is required for model promotion");
    }

    public enum Kind {
        INSTITUTIONAL,
        LOCAL_DEMO
    }
}
