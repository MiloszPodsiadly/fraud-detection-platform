package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.runtime.InstitutionalModePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class SensitiveReadAuditPolicy {

    private final Environment environment;
    private final boolean bankModeFailClosed;
    private final boolean sensitiveReadsFailClosed;

    public SensitiveReadAuditPolicy(
            Environment environment,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.sensitive-reads.audit.fail-closed:false}") boolean sensitiveReadsFailClosed
    ) {
        this.environment = environment;
        this.bankModeFailClosed = bankModeFailClosed;
        this.sensitiveReadsFailClosed = sensitiveReadsFailClosed;
    }

    public boolean failClosed() {
        return sensitiveReadsFailClosed || InstitutionalModePolicy.isInstitutionalMode(environment, bankModeFailClosed);
    }

    public boolean prodLikeProfile() {
        return InstitutionalModePolicy.hasInstitutionalProfile(environment);
    }
}
