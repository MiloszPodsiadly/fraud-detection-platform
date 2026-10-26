package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
class ModelPromotionAuthorization {

    private final CurrentAnalystUser currentAnalystUser;

    ModelPromotionAuthorization(CurrentAnalystUser currentAnalystUser) {
        this.currentAnalystUser = currentAnalystUser;
    }

    void requireAuthority(String requiredAuthority, ModelGovernanceScope governanceScope) {
        requirePrincipal(requiredAuthority, governanceScope);
    }

    String requireActor(String requiredAuthority, ModelGovernanceScope governanceScope) {
        AnalystPrincipal principal = requirePrincipal(requiredAuthority, governanceScope);
        ModelPromotionActorIdentity identity = ModelPromotionActorIdentity.fromPrincipal(principal);
        if (governanceScope.environment() == ModelGovernanceEnvironment.PRODUCTION
                && !identity.isInstitutional()) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.UNSUPPORTED_PRINCIPAL,
                    "Production model promotion requires a verified institutional actor");
        }
        return identity.actorId();
    }

    boolean canReadRationale(ModelGovernanceScope governanceScope) {
        AnalystPrincipal principal = currentAnalystUser.get().orElseThrow(() -> new ModelPromotionWorkflowBlockedException(
                ModelPromotionWorkflowBlockedException.Reason.UNAUTHENTICATED,
                "An authenticated analyst principal is required"
        ));
        return principal.hasAuthority(AnalystAuthority.MODEL_PROMOTION_RATIONALE_READ)
                && principal.hasAuthority(scopeAuthority(governanceScope.environment()));
    }

    AuthorizedActor authorize(
            String requiredAuthority,
            ModelGovernanceScope governanceScope,
            String operationId,
            Instant requestedAt
    ) {
        AnalystPrincipal principal = requirePrincipal(requiredAuthority, governanceScope);
        ModelPromotionActorIdentity actorIdentity = ModelPromotionActorIdentity.fromPrincipal(principal);
        if (governanceScope.environment() == ModelGovernanceEnvironment.PRODUCTION
                && !actorIdentity.isInstitutional()) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.UNSUPPORTED_PRINCIPAL,
                    "Production model promotion requires a verified institutional actor");
        }
        String authorizationFingerprint = authorizationFingerprint(principal, actorIdentity);
        return new AuthorizedActor(
                actorIdentity.actorId(),
                authorizationFingerprint,
                new ModelPromotionOperationContext(
                        operationId,
                        actorIdentity.actorId(),
                        authorizationFingerprint,
                        requestedAt,
                        actorIdentity
                )
        );
    }

    void revalidate(
            String requiredAuthority,
            ModelGovernanceScope governanceScope,
            ModelPromotionOperationContext expectedContext
    ) {
        AuthorizedActor current = authorize(
                requiredAuthority,
                governanceScope,
                expectedContext.operationId(),
                expectedContext.requestedAt()
        );
        if (!current.operationContext().actorId().equals(expectedContext.actorId())
                || !current.operationContext().authorizationFingerprint()
                .equals(expectedContext.authorizationFingerprint())
                || !java.util.Objects.equals(current.operationContext().actorIdentity(),
                        expectedContext.actorIdentity())) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.UNAUTHORIZED,
                    "Model promotion authorization changed before the decision could commit"
            );
        }
    }

    private AnalystPrincipal requirePrincipal(
            String requiredAuthority,
            ModelGovernanceScope governanceScope
    ) {
        AnalystPrincipal principal = currentAnalystUser.get().orElseThrow(() -> new ModelPromotionWorkflowBlockedException(
                ModelPromotionWorkflowBlockedException.Reason.UNAUTHENTICATED,
                "An authenticated analyst principal is required"
        ));
        if (!principal.hasAuthority(requiredAuthority)
                || !principal.hasAuthority(scopeAuthority(governanceScope.environment()))) {
            throw new ModelPromotionWorkflowBlockedException(
                    ModelPromotionWorkflowBlockedException.Reason.UNAUTHORIZED,
                    "The authenticated analyst is not authorized for this model promotion operation"
            );
        }
        return principal;
    }

    private String authorizationFingerprint(AnalystPrincipal principal, ModelPromotionActorIdentity actorIdentity) {
        List<String> roles = new ArrayList<>(principal.roles().stream().map(Enum::name).toList());
        List<String> authorities = new ArrayList<>(principal.authorities());
        roles.sort(String::compareTo);
        authorities.sort(String::compareTo);
        String material = "model-promotion-principal-v2\n" + actorIdentity.actorId()
                + "\nroles=" + String.join(",", roles)
                + "\nauthorities=" + String.join(",", authorities);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String scopeAuthority(ModelGovernanceEnvironment environment) {
        return switch (environment) {
            case DEVELOPMENT -> AnalystAuthority.MODEL_PROMOTION_SCOPE_DEVELOPMENT;
            case TEST -> AnalystAuthority.MODEL_PROMOTION_SCOPE_TEST;
            case PRODUCTION -> AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION;
        };
    }

    record AuthorizedActor(
            String actorId,
            String authorizationFingerprint,
            ModelPromotionOperationContext operationContext
    ) {
    }
}
