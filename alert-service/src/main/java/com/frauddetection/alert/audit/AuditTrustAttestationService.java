package com.frauddetection.alert.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSink;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorSummary;
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

    public AuditTrustAttestationService(
            AuditIntegrityService internalIntegrityService,
            ExternalAuditIntegrityService externalIntegrityService,
            ExternalAuditAnchorSink externalAnchorSink,
            AuditTrustAttestationSigner signer
    ) {
        this.internalIntegrityService = internalIntegrityService;
        this.externalIntegrityService = externalIntegrityService;
        this.externalAnchorSink = externalAnchorSink;
        this.signer = signer;
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
            AuditIntegrityResponse internal = internalIntegrityService.verify(null, null, normalizedSourceService, "HEAD", normalizedLimit);
            ExternalAuditIntegrityResponse external = externalIntegrityService.verify(normalizedSourceService, normalizedLimit);
            return response(normalizedSourceService, normalizedLimit, internal, external);
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
            AuditIntegrityResponse internal,
            ExternalAuditIntegrityResponse external
    ) {
        String externalAnchorStatus = externalAnchorStatus(external);
        AuditTrustLevel trustLevel = trustLevel(internal, external, externalAnchorStatus);
        String status = "UNAVAILABLE".equals(internal.status()) ? "UNAVAILABLE" : "AVAILABLE";
        AuditTrustAttestationResponse.AnchorCoverage coverage = anchorCoverage(external, externalAnchorStatus);
        Long latestChainPosition = latestChainPosition(external);
        String latestEventHash = latestEventHash(internal, external);
        AuditTrustAttestationResponse.ExternalAnchorReference latestExternalAnchor = latestExternalAnchor(external);
        List<String> limitations = limitations(internal, external, externalAnchorStatus, trustLevel);

        Map<String, Object> canonical = canonical(
                trustLevel,
                internal.status(),
                external.status(),
                externalAnchorStatus,
                coverage,
                latestChainPosition,
                latestEventHash,
                latestExternalAnchor,
                limitations
        );
        String fingerprint = sha256(canonicalBytes(canonical));
        AuditTrustAttestationSignature signature = signature(canonicalBytes(canonicalWithFingerprint(canonical, fingerprint)));
        AuditTrustLevel finalTrustLevel = trustLevel == AuditTrustLevel.EXTERNALLY_ANCHORED && signature != null
                ? AuditTrustLevel.SIGNED_ATTESTATION
                : trustLevel;

        if (finalTrustLevel != trustLevel) {
            canonical = canonical(
                    finalTrustLevel,
                    internal.status(),
                    external.status(),
                    externalAnchorStatus,
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
                coverage,
                latestChainPosition,
                latestEventHash,
                latestExternalAnchor,
                fingerprint,
                signature == null ? null : signature.signature(),
                signature == null ? null : signature.keyId(),
                signer.mode(),
                sourceService,
                limit,
                limitations
        );
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

    private AuditTrustAttestationResponse.ExternalAnchorReference latestExternalAnchor(ExternalAuditIntegrityResponse external) {
        ExternalAuditAnchorSummary anchor = external.externalAnchor();
        if (anchor == null) {
            return null;
        }
        return new AuditTrustAttestationResponse.ExternalAnchorReference(
                anchor.externalAnchorId(),
                anchor.chainPosition(),
                anchor.sinkType(),
                anchor.publicationStatus()
        );
    }

    private List<String> limitations(
            AuditIntegrityResponse internal,
            ExternalAuditIntegrityResponse external,
            String externalAnchorStatus,
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
        }
        if (trustLevel == AuditTrustLevel.INTERNAL_ONLY && signer.signingEnabled()) {
            limitations.add("local_signature_does_not_add_external_trust");
        }
        return List.copyOf(limitations);
    }

    private Map<String, Object> canonical(
            AuditTrustLevel trustLevel,
            String internalIntegrityStatus,
            String externalIntegrityStatus,
            String externalAnchorStatus,
            AuditTrustAttestationResponse.AnchorCoverage anchorCoverage,
            Long latestChainPosition,
            String latestEventHash,
            AuditTrustAttestationResponse.ExternalAnchorReference latestExternalAnchorReference,
            List<String> limitations
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("trust_level", trustLevel.name());
        canonical.put("internal_integrity_status", internalIntegrityStatus);
        canonical.put("external_integrity_status", externalIntegrityStatus);
        canonical.put("external_anchor_status", externalAnchorStatus);
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
            AuditTrustAttestationResponse.ExternalAnchorReference latestExternalAnchorReference
    ) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("external_anchor_id", latestExternalAnchorReference.externalAnchorId());
        reference.put("chain_position", latestExternalAnchorReference.chainPosition());
        reference.put("sink_type", latestExternalAnchorReference.sinkType());
        reference.put("publication_status", latestExternalAnchorReference.publicationStatus());
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
}
