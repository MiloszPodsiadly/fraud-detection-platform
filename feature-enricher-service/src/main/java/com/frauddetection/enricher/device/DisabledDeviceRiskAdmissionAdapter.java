package com.frauddetection.enricher.device;

import com.frauddetection.common.events.contract.TransactionRawEvent;

import java.util.Objects;

/** Explicit denial until an independently authenticated institutional source is integrated. */
public final class DisabledDeviceRiskAdmissionAdapter {
    public static final String DENIAL_CODE = "DEVICE_RISK_ADMISSION_DISABLED_NO_TRUSTED_ISSUER";

    public AdmissionDenied admit(TransactionRawEvent event) {
        Objects.requireNonNull(event, "event is required");
        return new AdmissionDenied(DENIAL_CODE);
    }

    public record AdmissionDenied(String reasonCode) {
        public AdmissionDenied {
            if (!DENIAL_CODE.equals(reasonCode)) {
                throw new IllegalArgumentException("DEVICE_RISK_ADMISSION_DENIAL_INVALID");
            }
        }
    }
}
