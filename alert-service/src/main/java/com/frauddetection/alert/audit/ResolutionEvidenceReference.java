package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

public record ResolutionEvidenceReference(
        ResolutionEvidenceType type,

        @Size(max = 500)
        String reference,

        @JsonProperty("verified_at")
        Instant verifiedAt,

        @JsonProperty("verified_by")
        @Size(max = 120)
        String verifiedBy
) {

    public ResolutionEvidenceReference {
        reference = normalize(reference, 500);
        verifiedBy = normalize(verifiedBy, 120);
    }

    public static ResolutionEvidenceReference require(
            ResolutionEvidenceReference evidence,
            String reasonCode
    ) {
        if (evidence == null
                || evidence.type() == null
                || evidence.reference() == null
                || evidence.reference().isBlank()
                || evidence.verifiedAt() == null
                || evidence.verifiedBy() == null
                || evidence.verifiedBy().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reasonCode);
        }
        return evidence;
    }

    public static void requireBrokerEvidence(ResolutionEvidenceReference evidence) {
        ResolutionEvidenceReference required = require(evidence, "resolution evidence is required");
        if (required.type() != ResolutionEvidenceType.BROKER_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "broker evidence is required for published resolution");
        }
    }

    private static String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
