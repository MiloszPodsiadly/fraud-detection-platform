package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Validated public trust metadata only; no signing or trust-file write capability. */
@Service
public class PolicyTrustRegistrySnapshotService {
    private final ObjectMapper mapper;
    private final String actorPath;
    private final String approvalPath;
    private final String issuerPath;
    private final long actorFloor;
    private final long approvalFloor;
    private final long issuerFloor;

    public PolicyTrustRegistrySnapshotService(ObjectMapper mapper,
            @Value("${app.model-promotion.online-actor-trust.path:}") String actorPath,
            @Value("${app.model-promotion.approval-trust.path:}") String approvalPath,
            @Value("${app.model-promotion.trusted-issuers.path:}") String issuerPath,
            @Value("${app.model-promotion.online-actor-trust.minimum-generation:1}") long actorFloor,
            @Value("${app.model-promotion.approval-trust.minimum-generation:1}") long approvalFloor,
            @Value("${app.model-promotion.trusted-issuers.minimum-generation:1}") long issuerFloor) {
        this.mapper = mapper;
        this.actorPath = actorPath;
        this.approvalPath = approvalPath;
        this.issuerPath = issuerPath;
        this.actorFloor = actorFloor;
        this.approvalFloor = approvalFloor;
        this.issuerFloor = issuerFloor;
    }

    public List<RegistryView> inspect(PolicyGovernanceActor actor) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.TRUST_READ)) {
            throw new AccessDeniedException("Institutional trust read mandate required");
        }
        List<RegistryView> views = List.of(snapshot(Registry.ONLINE_ACTORS),
                snapshot(Registry.APPROVAL_KEYS), snapshot(Registry.ISSUER_KEYS));
        if (actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) return views;
        return views.stream().map(view -> new RegistryView(view.registry(), view.status(),
                view.generation(), view.digestSha256(), view.rollbackFloor(),
                view.entries().stream().filter(entry -> !entry.environments().contains("PRODUCTION"))
                        .toList())).toList();
    }

    public RegistryView snapshot(Registry registry) {
        try {
            return switch (registry) {
                case ONLINE_ACTORS -> actors();
                case APPROVAL_KEYS -> approvals();
                case ISSUER_KEYS -> issuers();
            };
        } catch (RuntimeException unavailable) {
            return new RegistryView(registry, "UNAVAILABLE", null, null, floor(registry), List.of());
        }
    }

    public RegistryView requireReady(Registry registry) {
        RegistryView view = snapshot(registry);
        if (!"READY".equals(view.status())) throw new SecurityException("Validated trust registry unavailable");
        return view;
    }

    /** Returns the complete file-backed configuration only after the production parser validates it. */
    ValidatedRegistry validated(Registry kind) {
        return switch (kind) {
            case ONLINE_ACTORS -> {
                var configuration = read(actorPath, OnlinePolicyActorTrustRegistry.Configuration.class);
                var registry = OnlinePolicyActorTrustRegistry.fromConfiguration(configuration, actorFloor);
                yield new ValidatedRegistry(kind, configuration, registry.generation(),
                        registry.canonicalSha256(), actorFloor);
            }
            case APPROVAL_KEYS -> {
                var configuration = read(approvalPath, InternalPolicyApprovalTrustRegistry.Configuration.class);
                var registry = InternalPolicyApprovalTrustRegistry.fromConfiguration(configuration, approvalFloor);
                yield new ValidatedRegistry(kind, configuration, registry.generation(),
                        registry.canonicalSha256(), approvalFloor);
            }
            case ISSUER_KEYS -> {
                var configuration = read(issuerPath, ModelPromotionTrustedIssuerRegistry.Configuration.class);
                var registry = ModelPromotionTrustedIssuerRegistry.fromConfiguration(configuration, issuerFloor);
                yield new ValidatedRegistry(kind, configuration, registry.generation(),
                        registry.canonicalSha256(), issuerFloor);
            }
        };
    }

    private RegistryView actors() {
        var configuration = read(actorPath, OnlinePolicyActorTrustRegistry.Configuration.class);
        var registry = OnlinePolicyActorTrustRegistry.fromConfiguration(configuration, actorFloor);
        return new RegistryView(Registry.ONLINE_ACTORS, "READY", registry.generation(),
                registry.canonicalSha256(), actorFloor,
                registry.entries().stream().map(entry -> new Entry(entry.actorId(), null, null,
                        entry.displayName(), entry.roles().stream().map(Enum::name).sorted().toList(),
                        entry.institutionalScopes(), entry.environments().stream().map(Enum::name).toList(),
                        entry.intendedUses().stream().map(Enum::name).toList(),
                        entry.validFrom(), entry.validUntil(), entry.status().name())).toList());
    }

    private RegistryView approvals() {
        var configuration = read(approvalPath, InternalPolicyApprovalTrustRegistry.Configuration.class);
        var registry = InternalPolicyApprovalTrustRegistry.fromConfiguration(configuration, approvalFloor);
        return new RegistryView(Registry.APPROVAL_KEYS, "READY", registry.generation(),
                registry.canonicalSha256(), approvalFloor,
                registry.keys().stream().map(key -> new Entry(key.identity(), key.keyId(),
                        key.publicKeySha256(), null, key.roles().stream().map(Enum::name).sorted().toList(),
                        key.institutionalScopes().stream().sorted().toList(),
                        key.environments().stream().map(Enum::name).sorted().toList(),
                        key.intendedUses().stream().map(Enum::name).sorted().toList(),
                        key.validFrom().toString(), key.validUntil().toString(), key.status().name())).toList());
    }

    private RegistryView issuers() {
        var configuration = read(issuerPath, ModelPromotionTrustedIssuerRegistry.Configuration.class);
        var registry = ModelPromotionTrustedIssuerRegistry.fromConfiguration(configuration, issuerFloor);
        return new RegistryView(Registry.ISSUER_KEYS, "READY", registry.generation(),
                registry.canonicalSha256(), issuerFloor,
                registry.keys().stream().map(key -> new Entry(key.issuerIdentity(), key.keyId(),
                        key.publicKeySha256(), null, List.of("POLICY_ISSUER"),
                        key.institutionalScopes().stream().sorted().toList(),
                        key.environments().stream().map(Enum::name).sorted().toList(),
                        key.intendedUses().stream().map(Enum::name).sorted().toList(),
                        key.validFrom().toString(), key.validUntil().toString(), key.status().name())).toList());
    }

    private <T> T read(String path, Class<T> type) {
        if (path == null || path.isBlank()) throw new IllegalStateException("Registry path unavailable");
        try {
            Path source = Path.of(path);
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                throw new IllegalStateException("Registry file unavailable");
            }
            String json = Files.readString(source);
            strictSchema(mapper.readTree(json), type);
            return mapper.readValue(json, type);
        } catch (Exception failure) {
            throw new IllegalStateException("Registry file invalid or unavailable", failure);
        }
    }

    private void strictSchema(JsonNode root, Class<?> type) {
        boolean actor = type == OnlinePolicyActorTrustRegistry.Configuration.class;
        Set<String> rootFields = Set.of("version", "generation", actor ? "actors" : "keys");
        if (root == null || !root.isObject() || !fields(root).equals(rootFields)) {
            throw new IllegalStateException("Trust registry root schema differs");
        }
        JsonNode entries = root.path(actor ? "actors" : "keys");
        if (!entries.isArray()) throw new IllegalStateException("Trust registry entries are invalid");
        Set<String> required = actor
                ? Set.of("actorId", "issuer", "subject", "roles", "institutionalScopes",
                        "environments", "intendedUses", "validFrom", "validUntil", "status")
                : type == InternalPolicyApprovalTrustRegistry.Configuration.class
                    ? Set.of("identity", "keyId", "publicKeySpkiBase64", "publicKeySha256", "roles",
                            "institutionalScopes", "environments", "intendedUses", "validFrom",
                            "validUntil", "status")
                    : Set.of("issuerIdentity", "keyId", "publicKeySpkiBase64", "publicKeySha256",
                            "institutionalScopes", "environments", "intendedUses", "validFrom",
                            "validUntil", "status");
        for (JsonNode entry : entries) {
            if (!entry.isObject() || !fields(entry).containsAll(required)
                    || fields(entry).stream().anyMatch(field -> !required.contains(field)
                            && !(actor && "displayName".equals(field)))) {
                throw new IllegalStateException("Trust registry entry schema differs");
            }
        }
    }

    private Set<String> fields(JsonNode object) {
        return object.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private long floor(Registry registry) {
        return switch (registry) {
            case ONLINE_ACTORS -> actorFloor;
            case APPROVAL_KEYS -> approvalFloor;
            case ISSUER_KEYS -> issuerFloor;
        };
    }

    public enum Registry { ONLINE_ACTORS, APPROVAL_KEYS, ISSUER_KEYS }
    record ValidatedRegistry(Registry registry, Object configuration, long generation,
            String digestSha256, long rollbackFloor) { }
    public record RegistryView(Registry registry, String status, Long generation, String digestSha256,
            long rollbackFloor, List<Entry> entries) { }
    public record Entry(String identity, String keyId, String publicKeySha256, String displayName,
            List<String> roles, List<String> institutionalScopes, List<String> environments,
            List<String> intendedUses, String validFrom, String validUntil, String status) { }
}
