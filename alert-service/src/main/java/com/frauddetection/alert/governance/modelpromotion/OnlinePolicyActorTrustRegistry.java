package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

/** Deployment-controlled OIDC actor mandates shared by publication and read-side verification. */
final class OnlinePolicyActorTrustRegistry {
    static final String VERSION = "model-policy-online-actor-trust-v1";

    private final long generation;
    private final String canonicalSha256;
    private final Map<String, Actor> actors;

    private OnlinePolicyActorTrustRegistry(long generation, String canonicalSha256, Map<String, Actor> actors) {
        this.generation = generation;
        this.canonicalSha256 = canonicalSha256;
        this.actors = Map.copyOf(actors);
    }

    static OnlinePolicyActorTrustRegistry load(ObjectMapper mapper, Path path, long minimumGeneration) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IllegalStateException("Online actor mandates must be a readable regular file");
            }
            return fromConfiguration(mapper.readValue(Files.readString(path), Configuration.class),
                    minimumGeneration);
        } catch (IOException failure) {
            throw new IllegalStateException("Online actor mandates are unavailable", failure);
        }
    }

    static OnlinePolicyActorTrustRegistry fromConfiguration(Configuration configuration, long minimumGeneration) {
        if (configuration == null || !VERSION.equals(configuration.version()) || minimumGeneration < 1
                || configuration.generation() < minimumGeneration || configuration.actors() == null
                || configuration.actors().isEmpty()) {
            throw new IllegalStateException("Online actor mandate generation or entries are invalid");
        }
        Map<String, Actor> actors = new HashMap<>();
        for (Actor actor : configuration.actors()) {
            if (actor == null || actor.issuer() == null || actor.issuer().isBlank()
                    || actor.subject() == null || actor.subject().isBlank()
                    || !actorId(actor.issuer(), actor.subject()).equals(actor.actorId())
                    || !validActorSets(actor)
                    || actor.status() == null
                    || actor.displayName() != null && (actor.displayName().isBlank()
                            || actor.displayName().length() > 128)
                    || !ModelPromotionTrustedIssuerRegistry.canonicalTime(actor.validFrom(), "validFrom")
                            .isBefore(ModelPromotionTrustedIssuerRegistry.canonicalTime(actor.validUntil(), "validUntil"))
                    || actors.putIfAbsent(actor.actorId(), actor) != null) {
                throw new IllegalStateException("Online actor mandate is malformed or duplicated");
            }
        }
        return new OnlinePolicyActorTrustRegistry(configuration.generation(),
                GovernanceCanonicalJson.sha256(new ObjectMapper(), configuration), actors);
    }

    private static boolean validActorSets(Actor actor) {
        ModelPromotionTrustedIssuerRegistry.requiredSet(actor.roles(), "roles");
        ModelPromotionTrustedIssuerRegistry.requiredSet(actor.environments(), "environments");
        ModelPromotionTrustedIssuerRegistry.requiredSet(actor.intendedUses(), "intendedUses");
        return ModelPromotionTrustedIssuerRegistry.requiredSet(actor.institutionalScopes(),
                "institutionalScopes").equals(Set.of(ModelGovernanceScope.CURRENT_INSTITUTION));
    }

    void require(String actorId, String issuer, String subject, Role role,
            ModelGovernanceScope scope, ModelPromotionIntendedUse use, Instant actionAt, Instant now) {
        Actor actor = actors.get(actorId);
        if (actor == null || !actor.issuer().equals(issuer) || !actor.subject().equals(subject)
                || actor.status() != Status.ACTIVE || !actor.roles().contains(role)
                || !actor.institutionalScopes().contains(scope.institutionalScope())
                || !actor.environments().contains(scope.environment()) || !actor.intendedUses().contains(use)
                || actionAt.isBefore(Instant.parse(actor.validFrom()))
                || !actionAt.isBefore(Instant.parse(actor.validUntil()))
                || now.isBefore(Instant.parse(actor.validFrom()))
                || !now.isBefore(Instant.parse(actor.validUntil()))) {
            throw new SecurityException("Current independent OIDC actor mandate is unavailable");
        }
    }

    long generation() { return generation; }
    String canonicalSha256() { return canonicalSha256; }

    List<Actor> entries() {
        return actors.values().stream().sorted(java.util.Comparator.comparing(Actor::actorId)).toList();
    }

    List<Actor> eligibleApprovers(ModelGovernanceScope scope, ModelPromotionIntendedUse use, Instant now) {
        return actors.values().stream().filter(actor -> actor.status() == Status.ACTIVE
                && actor.roles().contains(Role.POLICY_APPROVER)
                && actor.institutionalScopes().contains(scope.institutionalScope())
                && actor.environments().contains(scope.environment())
                && actor.intendedUses().contains(use)
                && !now.isBefore(Instant.parse(actor.validFrom()))
                && now.isBefore(Instant.parse(actor.validUntil())))
                .sorted(java.util.Comparator.comparing(Actor::actorId)).toList();
    }

    private static String actorId(String issuer, String subject) {
        try {
            return "oidc:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((issuer + "#" + subject).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    record Configuration(String version, long generation, List<Actor> actors) { }
    record Actor(String actorId, String issuer, String subject, List<Role> roles,
            List<String> institutionalScopes, List<ModelGovernanceEnvironment> environments,
            List<ModelPromotionIntendedUse> intendedUses, String validFrom, String validUntil, Status status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String displayName) {
        Actor(String actorId, String issuer, String subject, List<Role> roles,
                List<String> institutionalScopes, List<ModelGovernanceEnvironment> environments,
                List<ModelPromotionIntendedUse> intendedUses, String validFrom, String validUntil, Status status) {
            this(actorId, issuer, subject, roles, institutionalScopes, environments,
                    intendedUses, validFrom, validUntil, status, null);
        }
    }
    enum Role { POLICY_AUTHOR, POLICY_APPROVER }
    enum Status { ACTIVE, REVOKED }
}
