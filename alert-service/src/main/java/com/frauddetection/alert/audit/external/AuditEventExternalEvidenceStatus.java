package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditExternalAnchorStatus;

public record AuditEventExternalEvidenceStatus(
        AuditExternalAnchorStatus externalAnchorStatus,
        String signatureStatus
) {
    public boolean externalPublished() {
        return externalAnchorStatus == AuditExternalAnchorStatus.PUBLISHED;
    }

    public boolean signatureValid() {
        return "VALID".equals(signatureStatus);
    }
}
