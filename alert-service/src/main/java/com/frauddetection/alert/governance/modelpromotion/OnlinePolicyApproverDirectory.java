package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** Directory view of deployment-owned approver mandates, not a role-granting IdP API. */
@Service
public class OnlinePolicyApproverDirectory {
    private final ObjectMapper mapper;
    private final String trustPath;
    private final long floor;
    private final Clock clock;

    @Autowired
    public OnlinePolicyApproverDirectory(ObjectMapper mapper,
            @Value("${app.model-promotion.online-actor-trust.path:}") String trustPath,
            @Value("${app.model-promotion.online-actor-trust.minimum-generation:1}") long floor) {
        this(mapper, trustPath, floor, Clock.systemUTC());
    }

    OnlinePolicyApproverDirectory(ObjectMapper mapper, String trustPath, long floor, Clock clock) {
        this.mapper = mapper;
        this.trustPath = trustPath;
        this.floor = floor;
        this.clock = clock;
    }

    public List<Entry> eligible(PolicyGovernanceActor caller, ModelGovernanceScope scope,
            ModelPromotionIntendedUse use) {
        if (caller == null || !caller.authorities().contains(ModelPolicyAuthority.DIRECTORY_READ)) {
            throw new AccessDeniedException("Approver directory mandate required");
        }
        if (scope == null || use == null || !ModelGovernanceScope.CURRENT_INSTITUTION
                .equals(scope.institutionalScope())) {
            throw new IllegalArgumentException("Supported policy scope and use required");
        }
        if (scope.environment() == ModelGovernanceEnvironment.PRODUCTION
                && !caller.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production directory scope required");
        }
        return registry().eligibleApprovers(scope, use, clock.instant()).stream()
                .filter(actor -> !actor.actorId().equals(caller.id()))
                .map(actor -> new Entry(actor.actorId(),
                        actor.displayName() == null || actor.displayName().isBlank()
                                ? "Institutional approver " + actor.actorId().substring(5, 13)
                                : actor.displayName(), scope.institutionalScope(),
                        scope.environment(), use, actor.validUntil()))
                .toList();
    }

    public void requireEligible(PolicyGovernanceActor approver, ModelGovernanceScope scope,
            ModelPromotionIntendedUse use) {
        OnlinePolicyActorTrustRegistry registry = registry();
        registry.require(approver.id(), approver.issuer(), approver.subject(),
                OnlinePolicyActorTrustRegistry.Role.POLICY_APPROVER, scope, use,
                clock.instant(), clock.instant());
    }

    private OnlinePolicyActorTrustRegistry registry() {
        if (trustPath == null || trustPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Approver mandate registry unavailable");
        }
        try {
            return OnlinePolicyActorTrustRegistry.load(mapper, Path.of(trustPath), floor);
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Approver mandate registry unavailable", unavailable);
        }
    }

    public record Entry(String actorId, String displayName, String institutionalScope,
            ModelGovernanceEnvironment environment, ModelPromotionIntendedUse intendedUse,
            String mandateValidUntil) { }
}
