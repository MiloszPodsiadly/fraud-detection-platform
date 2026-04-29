package com.frauddetection.alert.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSummary;
import com.frauddetection.alert.audit.external.ExternalAnchorReference;
import com.frauddetection.alert.audit.external.ExternalImmutabilityLevel;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityResponse;
import com.frauddetection.alert.audit.external.ExternalAuditIntegrityService;
import com.frauddetection.alert.audit.trust.AuditTrustAttestationException;
import com.frauddetection.alert.audit.trust.AuditTrustAttestationSignature;
import com.frauddetection.alert.audit.trust.AuditTrustAttestationSigner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuditTrustAttestationService {

    private static final String DEFAULT_SOURCE_SERVICE = "alert-service";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final AuditIntegrityService internalIntegrityService;
    private final ExternalAuditIntegrityService externalIntegrityService;
    private final ExternalAuditAnchorSink externalAnchorSink;
    private final AuditTrustAttestationSigner signer;
    private final AuditService auditService;

    public AuditTrustAttestationService(
            AuditIntegrityService internalIntegrityService,
            ExternalAuditIntegrityService externalIntegrityService,
            ExternalAuditAnchorSink externalAnchorSink,
            AuditTrustAttestationSigner signer,
            AuditService auditService
    ) {
        this.internalIntegrityService = internalIntegrityService;
        this.externalIntegrityService = externalIntegrityService;
        this.externalAnchorSink = externalAnchorSink;
        this.signer = signer;
        this.auditService = auditService;
    }

    public AuditTrustAttestationResponse attest(String sourceService, Integer limit, String mode) {
        if (StringUtils.hasText(mode) && !"HEAD".equalsIgnoreCase(mode.trim())) {
            throw new InvalidAuditEventQueryException(List.of("mode: only HEAD is supported for trust attestation"));
        }
        int normalizedLimit = normalizeLimit(limit);
        String normalizedSourceService = StringUtils.hasText(sourceService)
                ? sourceService.trim()
                : DEFAULT_SOURCE_SERVICE;
        try {
            AuditIntegrityResponse internal = internalIntegrityService.verifyScheduled(normalizedSourceService, normalizedLimit);
            ExternalAuditIntegrityResponse external = externalIntegrityService.verify(normalizedSourceService, normalizedLimit);
            AuditTrustAttestationResponse response = response(normalizedSourceService, normalizedLimit, "HEAD", internal, external);
            auditAttestationRead(response);
            return response;
        } catch (DataAccessException | AuditTrustAttestationException exception) {
            throw new AuditTrustAttestationUnavailableException();
        }
    }

    private int normalizeLimit(Integer limit) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        List<String> errors = new ArrayList<>();
        if (value <= 0) {
            errors.add("limit: must be greater than 0");
        }
        if (value > MAX_LIMIT) {
            errors.add("limit: must be less than or equal to " + MAX_LIMIT);
        }
        if (!errors.isEmpty()) {
            throw new InvalidAuditEventQueryException(errors);
        }
        return value;
    }

    private AuditTrustAttestationResponse response(
            String sourceService,
            int limit,
            String mode,
            AuditIntegrityResponse internal,
            ExternalAuditIntegrityResponse external
    ) {
        String externalAnchorStatus = externalAnchorStatus(external);
        ExternalImmutabilityLevel immutabilityLevel = external.externalImmutabilityLevel() == null
                ? ExternalImmutabilityLevel.NONE
                : external.externalImmutabilityLevel();
        validateExternalConsistency(external, externalAnchorStatus);
        AuditTrustLevel trustLevel = trustLevel(internal, external, externalAnchorStatus);
        String status = "UNAVAILABLE".equals(internal.status()) ? "UNAVAILABLE" : "AVAILABLE";
        AuditTrustAttestationResponse.AnchorCoverage coverage = anchorCoverage(external, externalAnchorStatus);
        Long latestChainPosition = latestChainPosition(external);
        String latestEventHash = latestEventHash(internal, external);
        ExternalAnchorReference latestExternalAnchor = latestExternalAnchor(external);
        List<String> limitations = limitations(internal, external, externalAnchorStatus, immutabilityLevel, trustLevel);
        String signatureKeyId = signer.signingEnabled() ? signer.keyId() : null;
        String signatureStrength = signer.signingEnabled() ? signer.signatureStrength() : "NONE";
        String externalTrustDependency = externalTrustDependency(trustLevel);

        Map<String, Object> canonical = canonical(
                sourceService,
                limit,
                mode,
                signer.mode(),
                signatureKeyId,
                trustLevel,
                internal.status(),
                external.status(),
                externalAnchorStatus,
                immutabilityLevel,
                coverage,
                latestChainPosition,
                latestEventHash,
                latestExternalAnchor,
                limitations
        );
        String fingerprint = sha256(canonicalBytes(canonical));
        AuditTrustAttestationSignature signature = signature(canonicalBytes(canonicalWithFingerprint(canonical, fingerprint)));
        AuditTrustLevel finalTrustLevel = trustLevel == AuditTrustLevel.EXTERNALLY_ANCHORED
                && signedByLocalAuthority(external, latestExternalAnchor)
                ? AuditTrustLevel.SIGNED_BY_LOCAL_AUTHORITY
                : trustLevel;
        finalTrustLevel = finalTrustLevel == AuditTrustLevel.EXTERNALLY_ANCHORED
                && "PRODUCTION_READY".equals(signatureStrength)
                && immutabilityLevel == ExternalImmutabilityLevel.ENFORCED
                ? AuditTrustLevel.SIGNED_ATTESTATION
                : finalTrustLevel;

        if (finalTrustLevel != trustLevel) {
            canonical = canonical(
                    sourceService,
                    limit,
                    mode,
                    signer.mode(),
                    signatureKeyId,
                    finalTrustLevel,
                    internal.status(),
                    external.status(),
                    externalAnchorStatus,
                    immutabilityLevel,
                    coverage,
                    latestChainPosition,
                    latestEventHash,
                    latestExternalAnchor,
                    limitations
            );
            fingerprint = sha256(canonicalBytes(canonical));
            signature = signature(canonicalBytes(canonicalWithFingerprint(canonical, fingerprint)));
        }

        return new AuditTrustAttestationResponse(
                status,
                finalTrustLevel,
                internal.status(),
                external.status(),
                externalAnchorStatus,
                immutabilityLevel,
                coverage,
                latestChainPosition,
                latestEventHash,
                latestExternalAnchor,
                fingerprint,
                signature == null ? null : signature.signature(),
                signature == null ? null : signature.keyId(),
                signer.mode(),
                signatureStrength,
                externalTrustDependency,
                sourceService,
                limit,
                limitations
        );
    }

    void validateExternalConsistency(ExternalAuditIntegrityResponse external, String externalAnchorStatus) {
        if ("VALID".equals(externalAnchorStatus) && !"VALID".equals(external.status())) {
            throw new AuditTrustAttestationUnavailableException();
        }
    }

    private AuditTrustLevel trustLevel(
            AuditIntegrityResponse internal,
            ExternalAuditIntegrityResponse external,
            String externalAnchorStatus
    ) {
        if ("UNAVAILABLE".equals(internal.status())) {
            return AuditTrustLevel.UNAVAILABLE;
        }
        if (!"VALID".equals(internal.status())) {
            return AuditTrustLevel.INTERNAL_ONLY;
        }
        if ("VALID".equals(external.status()) && "VALID".equals(externalAnchorStatus)) {
            return AuditTrustLevel.EXTERNALLY_ANCHORED;
        }
        if (!"DISABLED".equals(externalAnchorStatus) && !"MISSING".equals(externalAnchorStatus)) {
            return AuditTrustLevel.PARTIAL_EXTERNAL;
        }
        return AuditTrustLevel.INTERNAL_ONLY;
    }

    private String externalAnchorStatus(ExternalAuditIntegrityResponse external) {
        if ("disabled".equals(externalAnchorSink.sinkType())) {
            return "DISABLED";
        }
        if ("VALID".equals(external.status()) && external.externalAnchor() != null) {
            return "VALID";
        }
        if ("UNAVAILABLE".equals(external.status())) {
            return "UNAVAILABLE";
        }
        if ("EXTERNAL_ANCHOR_MISSING".equals(external.reasonCode())) {
            return "MISSING";
        }
        if ("STALE_EXTERNAL_ANCHOR".equals(external.reasonCode())) {
            return "STALE";
        }
        return external.status();
    }

    private AuditTrustAttestationResponse.AnchorCoverage anchorCoverage(
            ExternalAuditIntegrityResponse external,
            String externalAnchorStatus
    ) {
        if (external.localAnchor() == null) {
            return AuditTrustAttestationResponse.AnchorCoverage.empty();
        }
        int matched = "VALID".equals(externalAnchorStatus) ? 1 : 0;
        int missing = matched == 1 ? 0 : 1;
        return new AuditTrustAttestationResponse.AnchorCoverage(1, matched, missing, matched);
    }

    private Long latestChainPosition(ExternalAuditIntegrityResponse external) {
        if (external.localAnchor() != null) {
            return external.localAnchor().chainPosition();
        }
        if (external.externalAnchor() != null) {
            return external.externalAnchor().chainPosition();
        }
        return null;
    }

    private String latestEventHash(AuditIntegrityResponse internal, ExternalAuditIntegrityResponse external) {
        if (internal.lastEventHash() != null) {
            return internal.lastEventHash();
        }
        if (external.localAnchor() != null) {
            return external.localAnchor().lastEventHash();
        }
        return external.externalAnchor() == null ? null : external.externalAnchor().lastEventHash();
    }

    private ExternalAnchorReference latestExternalAnchor(ExternalAuditIntegrityResponse external) {
        ExternalAuditAnchorSummary anchor = external.externalAnchor();
        if (anchor == null) {
            return null;
        }
        if (anchor.externalReference() != null) {
            return anchor.externalReference();
        }
        return new ExternalAnchorReference(
                anchor.localAnchorId(),
                anchor.externalAnchorId(),
                anchor.lastEventHash(),
                anchor.lastEventHash(),
                anchor.createdAt()
        );
    }

    private List<String> limitations(
            AuditIntegrityResponse internal,
            ExternalAuditIntegrityResponse external,
            String externalAnchorStatus,
            ExternalImmutabilityLevel immutabilityLevel,
            AuditTrustLevel trustLevel
    ) {
        List<String> limitations = new ArrayList<>();
        limitations.add("not_legal_notarization");
        limitations.add("not_worm_storage");
        limitations.add("not_siem_integration");
        limitations.add("not_kms_hsm_signing_unless_explicitly_integrated");
        limitations.add("derived_from_fdp19_fdp20_source_of_truth");
        if (!"VALID".equals(internal.status())) {
            limitations.add("internal_integrity_not_valid");
        }
        if (!"VALID".equals(external.status())) {
            limitations.add("external_integrity_not_valid");
        }
        if (!"VALID".equals(externalAnchorStatus)) {
            limitations.add("external_anchor_not_valid");
            limitations.add("external_trust_incomplete");
        }
        if (immutabilityLevel != ExternalImmutabilityLevel.ENFORCED) {
            limitations.add("external_immutability_not_enforced");
            limitations.add("no_worm_claim_without_infrastructure_enforcement");
        }
        if ("LOCAL_DEV".equals(signer.signatureStrength())) {
            limitations.add("local_signature_not_external_trust");
        }
        if (trustLevel == AuditTrustLevel.INTERNAL_ONLY && signer.signingEnabled()) {
            limitations.add("local_signature_does_not_add_external_trust");
        }
        limitations.add("local_trust_authority_not_kms_hsm");
        limitations.add("local_trust_authority_not_legal_notarization");
        return List.copyOf(limitations);
    }

    private String externalTrustDependency(AuditTrustLevel trustLevel) {
        if (trustLevel == AuditTrustLevel.EXTERNALLY_ANCHORED
                || trustLevel == AuditTrustLevel.SIGNED_BY_LOCAL_AUTHORITY
                || trustLevel == AuditTrustLevel.INDEPENDENTLY_VERIFIABLE
                || trustLevel == AuditTrustLevel.SIGNED_ATTESTATION) {
            return "REQUIRED";
        }
        return "OPTIONAL";
    }

    private boolean signedByLocalAuthority(ExternalAuditIntegrityResponse external, ExternalAnchorReference latestExternalAnchor) {
        return latestExternalAnchor != null
                && "VALID".equals(external.signatureVerificationStatus())
                && "SIGNED".equals(latestExternalAnchor.signatureStatus())
                && latestExternalAnchor.signingKeyId() != null
                && latestExternalAnchor.signature() != null
                && "Ed25519".equals(latestExternalAnchor.signingAlgorithm())
                && latestExternalAnchor.signedPayloadHash() != null
                && latestExternalAnchor.signingAuthority() != null
                && !"alert-service".equals(latestExternalAnchor.signingAuthority());
    }

    private Map<String, Object> canonical(
            String sourceService,
            int limit,
            String mode,
            String signerMode,
            String signatureKeyId,
            AuditTrustLevel trustLevel,
            String internalIntegrityStatus,
            String externalIntegrityStatus,
            String externalAnchorStatus,
            ExternalImmutabilityLevel immutabilityLevel,
            AuditTrustAttestationResponse.AnchorCoverage anchorCoverage,
            Long latestChainPosition,
            String latestEventHash,
            ExternalAnchorReference latestExternalAnchorReference,
            List<String> limitations
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("source_service", sourceService);
        canonical.put("limit", limit);
        canonical.put("mode", mode);
        canonical.put("signer_mode", signerMode);
        canonical.put("signature_key_id", signatureKeyId);
        canonical.put("trust_level", trustLevel.name());
        canonical.put("internal_integrity_status", internalIntegrityStatus);
        canonical.put("external_integrity_status", externalIntegrityStatus);
        canonical.put("external_anchor_status", externalAnchorStatus);
        canonical.put("external_immutability_level", immutabilityLevel.name());
        canonical.put("anchor_coverage", Map.of(
                "total_anchors_checked", anchorCoverage.totalAnchorsChecked(),
                "external_anchors_matched", anchorCoverage.externalAnchorsMatched(),
                "external_anchors_missing", anchorCoverage.externalAnchorsMissing(),
                "coverage_ratio", anchorCoverage.coverageRatio()
        ));
        canonical.put("latest_chain_position", latestChainPosition);
        canonical.put("latest_event_hash", latestEventHash);
        canonical.put("latest_external_anchor_reference", latestExternalAnchorReference == null
                ? null
                : externalAnchorReference(latestExternalAnchorReference));
        canonical.put("limitations", limitations);
        return canonical;
    }

    private Map<String, Object> externalAnchorReference(
            ExternalAnchorReference latestExternalAnchorReference
    ) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("anchor_id", latestExternalAnchorReference.anchorId());
        reference.put("external_key", latestExternalAnchorReference.externalKey());
        reference.put("anchor_hash", latestExternalAnchorReference.anchorHash());
        reference.put("external_hash", latestExternalAnchorReference.externalHash());
        reference.put("verified_at", latestExternalAnchorReference.verifiedAt() == null ? null : latestExternalAnchorReference.verifiedAt().toString());
        reference.put("signature_status", latestExternalAnchorReference.signatureStatus());
        reference.put("signing_key_id", latestExternalAnchorReference.signingKeyId());
        reference.put("signing_algorithm", latestExternalAnchorReference.signingAlgorithm());
        reference.put("signed_at", latestExternalAnchorReference.signedAt() == null ? null : latestExternalAnchorReference.signedAt().toString());
        reference.put("signing_authority", latestExternalAnchorReference.signingAuthority());
        reference.put("signed_payload_hash", latestExternalAnchorReference.signedPayloadHash());
        return reference;
    }

    private Map<String, Object> canonicalWithFingerprint(Map<String, Object> canonical, String fingerprint) {
        Map<String, Object> signedPayload = new LinkedHashMap<>(canonical);
        signedPayload.put("attestation_fingerprint", fingerprint);
        return signedPayload;
    }

    private byte[] canonicalBytes(Map<String, Object> canonical) {
        try {
            return CANONICAL_JSON.writeValueAsBytes(canonical);
        } catch (JsonProcessingException exception) {
            throw new AuditTrustAttestationUnavailableException();
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new AuditTrustAttestationUnavailableException();
        }
    }

    private AuditTrustAttestationSignature signature(byte[] canonicalBytes) {
        if (!signer.signingEnabled()) {
            return null;
        }
        return signer.sign(canonicalBytes)
                .orElseThrow(AuditTrustAttestationUnavailableException::new);
    }

    private void auditAttestationRead(AuditTrustAttestationResponse response) {
        auditService.audit(
                AuditAction.READ_AUDIT_TRUST_ATTESTATION,
                AuditResourceType.AUDIT_TRUST_ATTESTATION,
                null,
                null,
                "audit-trust-attestation-reader",
                AuditOutcome.SUCCESS,
                null,
                AuditEventMetadataSummary.trustAttestation(
                        response.sourceService(),
                        "1.0",
                        response.limit(),
                        response.trustLevel().name(),
                        response.internalIntegrityStatus(),
                        response.externalIntegrityStatus(),
                        response.externalAnchorStatus(),
                        response.attestationFingerprint()
                )
        );
    }
}
