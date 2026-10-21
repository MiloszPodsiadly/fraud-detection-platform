package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
            return mapper.readValue(Files.readString(source), type);
        } catch (Exception failure) {
            throw new IllegalStateException("Registry file invalid or unavailable", failure);
        }
    }

    private long floor(Registry registry) {
        return switch (registry) {
            case ONLINE_ACTORS -> actorFloor;
            case APPROVAL_KEYS -> approvalFloor;
            case ISSUER_KEYS -> issuerFloor;
        };
    }

    public enum Registry { ONLINE_ACTORS, APPROVAL_KEYS, ISSUER_KEYS }
    public record RegistryView(Registry registry, String status, Long generation, String digestSha256,
            long rollbackFloor, List<Entry> entries) { }
    public record Entry(String identity, String keyId, String publicKeySha256, String displayName,
            List<String> roles, List<String> institutionalScopes, List<String> environments,
            List<String> intendedUses, String validFrom, String validUntil, String status) { }
}
