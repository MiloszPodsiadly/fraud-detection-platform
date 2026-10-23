package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Builds one complete registry from a parser-validated protected source and one structured mutation. */
final class PolicyTrustCandidateBuilder {
    private final PolicyTrustRegistrySnapshotService snapshots;
    private final ObjectMapper mapper;
    private final Clock clock;

    PolicyTrustCandidateBuilder(PolicyTrustRegistrySnapshotService snapshots, ObjectMapper mapper, Clock clock) {
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.clock = clock;
    }

    Candidate build(PolicyTrustChangeService.Intent intent) {
        var base = snapshots.validated(intent.registry());
        if (base.generation() < base.rollbackFloor()
                || intent.requestedGeneration() != base.generation() + 1) {
            throw new IllegalArgumentException("Trust registry generation is stale or below the protected floor");
        }
        return switch (intent.registry()) {
            case ONLINE_ACTORS -> actorCandidate(intent, base);
            case APPROVAL_KEYS -> approvalCandidate(intent, base);
            case ISSUER_KEYS -> issuerCandidate(intent, base);
        };
    }

    private Candidate actorCandidate(PolicyTrustChangeService.Intent intent,
            PolicyTrustRegistrySnapshotService.ValidatedRegistry base) {
        var source = (OnlinePolicyActorTrustRegistry.Configuration) base.configuration();
        var actors = new ArrayList<>(source.actors());
        Object previous = null;
        Object proposed;
        if (intent.action() == PolicyTrustChangeService.Action.GRANT) {
            if (actors.stream().anyMatch(actor -> actor.actorId().equals(intent.targetIdentity()))) {
                throw new IllegalArgumentException("Actor identity already exists, including revoked history");
            }
            if (intent.targetIssuer() == null || intent.targetSubject() == null
                    || !PolicyGovernanceActor.identityId(intent.targetIssuer(), intent.targetSubject())
                            .equals(intent.targetIdentity())) {
                throw new IllegalArgumentException("OIDC issuer and subject must match exact target identity");
            }
            Instant from = timestamp(intent.validFrom(), "validFrom");
            Instant until = timestamp(intent.validUntil(), "validUntil");
            if (!from.isBefore(until)) throw new IllegalArgumentException("Actor validity window is invalid");
            var roles = intent.roles().stream().map(OnlinePolicyActorTrustRegistry.Role::valueOf)
                    .sorted(Comparator.comparing(Enum::name)).toList();
            var added = new OnlinePolicyActorTrustRegistry.Actor(intent.targetIdentity(), intent.targetIssuer(),
                    intent.targetSubject(), roles, List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                    List.of(intent.environment()), List.of(intent.intendedUse()), intent.validFrom(),
                    intent.validUntil(), OnlinePolicyActorTrustRegistry.Status.ACTIVE, intent.displayName());
            actors.add(added);
            proposed = added;
        } else if (intent.action() == PolicyTrustChangeService.Action.REVOKE) {
            int index = indexOfActor(actors, intent.targetIdentity());
            var old = actors.get(index);
            if (old.status() != OnlinePolicyActorTrustRegistry.Status.ACTIVE
                    || !sameRoles(intent.roles(), old.roles().stream().map(Enum::name).toList())
                    || !old.environments().contains(intent.environment())
                    || !old.intendedUses().contains(intent.intendedUse())) {
                throw new IllegalArgumentException("Actor revocation does not match an active exact mandate");
            }
            if (intent.targetIssuer() != null && !old.issuer().equals(intent.targetIssuer())
                    || intent.targetSubject() != null && !old.subject().equals(intent.targetSubject())) {
                throw new IllegalArgumentException("Actor identity changed");
            }
            var revoked = new OnlinePolicyActorTrustRegistry.Actor(old.actorId(), old.issuer(), old.subject(),
                    old.roles(), old.institutionalScopes(), old.environments(), old.intendedUses(),
                    old.validFrom(), old.validUntil(), OnlinePolicyActorTrustRegistry.Status.REVOKED,
                    old.displayName());
            actors.set(index, revoked);
            previous = old;
            proposed = revoked;
        } else {
            throw new IllegalArgumentException("Unsupported online actor mutation");
        }
        var configuration = new OnlinePolicyActorTrustRegistry.Configuration(source.version(),
                intent.requestedGeneration(), List.copyOf(actors));
        OnlinePolicyActorTrustRegistry.fromConfiguration(configuration, base.rollbackFloor());
        return candidate(intent, base, configuration, previous, proposed, null,
                source.actors().stream().anyMatch(actor -> actor.actorId().equals(intent.targetIdentity())
                        && actor.environments().contains(ModelGovernanceEnvironment.PRODUCTION)));
    }

    private Candidate approvalCandidate(PolicyTrustChangeService.Intent intent,
            PolicyTrustRegistrySnapshotService.ValidatedRegistry base) {
        var source = (InternalPolicyApprovalTrustRegistry.Configuration) base.configuration();
        var keys = new ArrayList<>(source.keys());
        var change = keyChange(intent, keys.stream().map(key -> new KeyEntry(key.identity(), key.keyId(),
                key.publicKeySpkiBase64(), key.publicKeySha256(), key.roles().stream().map(Enum::name).toList(),
                key.institutionalScopes(), key.environments(), key.intendedUses(), key.validFrom(),
                key.validUntil(), key.status())).toList());
        if (change.oldIndex() >= 0) keys.set(change.oldIndex(), approvalKey(change.updatedOld()));
        if (change.added() != null) keys.add(approvalKey(change.added()));
        var configuration = new InternalPolicyApprovalTrustRegistry.Configuration(source.version(),
                intent.requestedGeneration(), List.copyOf(keys));
        InternalPolicyApprovalTrustRegistry.fromConfiguration(configuration, base.rollbackFloor());
        return candidate(intent, base, configuration,
                change.previous() == null ? null : approvalKey(change.previous()),
                approvalKey(change.proposed()),
                change.previous() == null || change.added() == null
                        ? null : approvalKey(change.updatedOld()),
                change.affectsProduction());
    }

    private Candidate issuerCandidate(PolicyTrustChangeService.Intent intent,
            PolicyTrustRegistrySnapshotService.ValidatedRegistry base) {
        var source = (ModelPromotionTrustedIssuerRegistry.Configuration) base.configuration();
        var keys = new ArrayList<>(source.keys());
        var change = keyChange(intent, keys.stream().map(key -> new KeyEntry(key.issuerIdentity(), key.keyId(),
                key.publicKeySpkiBase64(), key.publicKeySha256(), List.of("POLICY_ISSUER"),
                key.institutionalScopes(), key.environments(), key.intendedUses(), key.validFrom(),
                key.validUntil(), key.status())).toList());
        if (change.oldIndex() >= 0) keys.set(change.oldIndex(), issuerKey(change.updatedOld()));
        if (change.added() != null) keys.add(issuerKey(change.added()));
        var configuration = new ModelPromotionTrustedIssuerRegistry.Configuration(source.version(),
                intent.requestedGeneration(), List.copyOf(keys));
        ModelPromotionTrustedIssuerRegistry.fromConfiguration(configuration, base.rollbackFloor());
        return candidate(intent, base, configuration,
                change.previous() == null ? null : issuerKey(change.previous()),
                issuerKey(change.proposed()),
                change.previous() == null || change.added() == null
                        ? null : issuerKey(change.updatedOld()),
                change.affectsProduction());
    }

    private KeyChange keyChange(PolicyTrustChangeService.Intent intent, List<KeyEntry> source) {
        int oldIndex = -1;
        KeyEntry old = null;
        if (intent.action() != PolicyTrustChangeService.Action.ADD) {
            String oldId = intent.action() == PolicyTrustChangeService.Action.ROTATE
                    ? intent.replacesKeyId() : intent.keyId();
            for (int index = 0; index < source.size(); index++) {
                if (source.get(index).keyId().equals(oldId)) { oldIndex = index; old = source.get(index); break; }
            }
            if (old == null || !old.identity().equals(intent.targetIdentity())
                    || !old.environments().contains(intent.environment())
                    || !old.intendedUses().contains(intent.intendedUse())
                    || !sameRoles(intent.roles(), old.roles())) {
                throw new IllegalArgumentException("Exact existing trust key and mandates are required");
            }
            if (intent.action() != PolicyTrustChangeService.Action.ROTATE
                    && !old.publicKeySha256().equals(intent.publicKeySha256())) {
                throw new IllegalArgumentException("Existing public-key fingerprint differs");
            }
        }
        KeyEntry added = null;
        KeyEntry updatedOld = old;
        if (intent.action() == PolicyTrustChangeService.Action.ADD
                || intent.action() == PolicyTrustChangeService.Action.ROTATE) {
            if (source.stream().anyMatch(key -> key.keyId().equals(intent.keyId())
                    || key.publicKeySha256().equals(intent.publicKeySha256()))) {
                throw new IllegalArgumentException("Key ID or fingerprint was already used");
            }
            verifyNewKey(intent);
            Instant from = timestamp(intent.validFrom(), "validFrom");
            Instant until = timestamp(intent.validUntil(), "validUntil");
            if (!from.isBefore(until)) throw new IllegalArgumentException("Key validity window is invalid");
            if (intent.action() == PolicyTrustChangeService.Action.ROTATE
                    && (from.isAfter(clock.instant()) || !clock.instant().isBefore(until))) {
                throw new IllegalArgumentException("Rotated key must be effective at the old-key cutoff");
            }
            added = new KeyEntry(intent.targetIdentity(), intent.keyId(), intent.publicKeySpkiBase64(),
                    intent.publicKeySha256(), sorted(intent.roles()),
                    old == null ? List.of(ModelGovernanceScope.CURRENT_INSTITUTION) : old.scopes(),
                    old == null ? List.of(intent.environment()) : old.environments(),
                    old == null ? List.of(intent.intendedUse()) : old.intendedUses(),
                    intent.validFrom(), intent.validUntil(), ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
            if (old != null) {
                if (old.status() != ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE) {
                    throw new IllegalArgumentException("Only an active key can be rotated");
                }
                updatedOld = withStatus(old, ModelPromotionTrustedIssuerRegistry.KeyStatus.RETIRED,
                        retirementCutoff(intent.retireAt(), old));
            }
        } else if (intent.action() == PolicyTrustChangeService.Action.RETIRE) {
            if (old.status() != ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE) {
                throw new IllegalArgumentException("Only active key can be retired");
            }
            updatedOld = withStatus(old, ModelPromotionTrustedIssuerRegistry.KeyStatus.RETIRED,
                    retirementCutoff(intent.retireAt(), old));
        } else if (intent.action() == PolicyTrustChangeService.Action.REVOKE) {
            if (old.status() == ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED) {
                throw new IllegalArgumentException("Key is already revoked");
            }
            updatedOld = withStatus(old, ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED,
                    old.validUntil());
        } else {
            throw new IllegalArgumentException("Unsupported key mutation");
        }
        boolean production = intent.environment() == ModelGovernanceEnvironment.PRODUCTION
                || old != null && old.environments().contains(ModelGovernanceEnvironment.PRODUCTION)
                || added != null && added.environments().contains(ModelGovernanceEnvironment.PRODUCTION);
        return new KeyChange(oldIndex, old, updatedOld, added, added == null ? updatedOld : added,
                production);
    }

    private void verifyNewKey(PolicyTrustChangeService.Intent intent) {
        try {
            ModelPromotionTrustedIssuerRegistry.publicKey(intent.publicKeySpkiBase64(),
                    intent.publicKeySha256());
        } catch (Exception failure) {
            throw new IllegalArgumentException("New public-key fingerprint or SPKI is invalid", failure);
        }
    }

    private String retirementCutoff(String value, KeyEntry old) {
        Instant cutoff = timestamp(value, "retireAt");
        if (!cutoff.isAfter(timestamp(old.validFrom(), "validFrom"))
                || cutoff.isAfter(timestamp(old.validUntil(), "validUntil"))
                || cutoff.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Retirement cutoff is outside the existing window or future");
        }
        return value;
    }

    private KeyEntry withStatus(KeyEntry old, ModelPromotionTrustedIssuerRegistry.KeyStatus status,
            String validUntil) {
        return new KeyEntry(old.identity(), old.keyId(), old.spki(), old.publicKeySha256(),
                old.roles(), old.scopes(), old.environments(), old.intendedUses(), old.validFrom(),
                validUntil, status);
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey approvalKey(KeyEntry value) {
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(value.identity(), value.keyId(),
                value.spki(), value.publicKeySha256(), value.roles().stream()
                        .map(InternalPolicyApprovalTrustRegistry.Role::valueOf).toList(),
                value.scopes(), value.environments(), value.intendedUses(), value.validFrom(),
                value.validUntil(), value.status());
    }

    private ModelPromotionTrustedIssuerRegistry.ConfiguredKey issuerKey(KeyEntry value) {
        return new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(value.identity(), value.keyId(),
                value.spki(), value.publicKeySha256(), value.scopes(), value.environments(),
                value.intendedUses(), value.validFrom(), value.validUntil(), value.status());
    }

    private Candidate candidate(PolicyTrustChangeService.Intent intent,
            PolicyTrustRegistrySnapshotService.ValidatedRegistry base, Object configuration,
            Object previous, Object proposed, Object updatedExisting, boolean existingProduction) {
        String canonicalJson = new String(GovernanceCanonicalJson.serialize(mapper,
                mapper.valueToTree(configuration)), StandardCharsets.UTF_8);
        String digest = GovernanceCanonicalJson.sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
        if (digest.equals(base.digestSha256())
                || intent.candidateRegistrySha256() != null
                    && !intent.candidateRegistrySha256().equals(digest)) {
            throw new IllegalArgumentException("Claimed candidate digest differs from the server-built registry");
        }
        var before = mapper.valueToTree(previous);
        var after = mapper.valueToTree(proposed);
        var diff = new Diff(intent.action().name(), intent.targetIdentity(), intent.keyId(),
                before, after, mapper.valueToTree(updatedExisting), removed(previous),
                List.of(PolicyTrustChangeService.Action.GRANT, PolicyTrustChangeService.Action.ADD,
                        PolicyTrustChangeService.Action.ROTATE).contains(intent.action())
                        ? sorted(intent.roles()) : List.of(),
                base.generation(), intent.requestedGeneration(), digest);
        String summary = intent.action() + " " + intent.targetIdentity()
                + (intent.keyId() == null ? "" : " / " + intent.keyId())
                + " in " + intent.registry() + ": generation " + base.generation()
                + " to " + intent.requestedGeneration() + ", SHA-256 " + digest;
        return new Candidate(intent.registry(), base.generation(), base.digestSha256(),
                intent.requestedGeneration(), canonicalJson, digest, diff, summary,
                existingProduction || intent.environment() == ModelGovernanceEnvironment.PRODUCTION);
    }

    private List<String> removed(Object previous) {
        if (previous == null) return List.of();
        if (previous instanceof OnlinePolicyActorTrustRegistry.Actor actor) {
            return actor.status() == OnlinePolicyActorTrustRegistry.Status.ACTIVE
                    ? actor.roles().stream().map(Enum::name).sorted().toList() : List.of();
        }
        if (previous instanceof InternalPolicyApprovalTrustRegistry.ConfiguredKey key) {
            return key.roles().stream().map(Enum::name).sorted().toList();
        }
        if (previous instanceof ModelPromotionTrustedIssuerRegistry.ConfiguredKey) {
            return List.of("POLICY_ISSUER");
        }
        return List.of();
    }

    private int indexOfActor(List<OnlinePolicyActorTrustRegistry.Actor> actors, String id) {
        for (int index = 0; index < actors.size(); index++) {
            if (actors.get(index).actorId().equals(id)) return index;
        }
        throw new IllegalArgumentException("Exact actor mandate does not exist");
    }

    private boolean sameRoles(List<String> left, List<String> right) {
        return Set.copyOf(left).equals(Set.copyOf(right)) && left.size() == right.size();
    }

    private List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private Instant timestamp(String value, String field) {
        return ModelPromotionTrustedIssuerRegistry.canonicalTime(value, field);
    }

    record Candidate(PolicyTrustRegistrySnapshotService.Registry registry, long baseGeneration,
            String baseSha256, long generation, String canonicalJson, String sha256, Diff diff,
            String summary, boolean affectsProduction) { }
    record Diff(String action, String targetIdentity, String keyId,
            tools.jackson.databind.JsonNode existingEntry, tools.jackson.databind.JsonNode proposedEntry,
            tools.jackson.databind.JsonNode updatedExistingEntry,
            List<String> removedAuthorities, List<String> addedAuthorities,
            long baseGeneration, long candidateGeneration, String candidateSha256) { }
    private record KeyEntry(String identity, String keyId, String spki, String publicKeySha256,
            List<String> roles, List<String> scopes, List<ModelGovernanceEnvironment> environments,
            List<ModelPromotionIntendedUse> intendedUses, String validFrom, String validUntil,
            ModelPromotionTrustedIssuerRegistry.KeyStatus status) { }
    private record KeyChange(int oldIndex, KeyEntry previous, KeyEntry updatedOld,
            KeyEntry added, KeyEntry proposed, boolean affectsProduction) { }
}
