package com.frauddetection.alert.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuditIntegrityScheduledVerifier {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityScheduledVerifier.class);
    private static final String SOURCE_SERVICE = "alert-service";
    private static final int WINDOW_LIMIT = 500;

    private final AuditIntegrityService auditIntegrityService;

    public AuditIntegrityScheduledVerifier(AuditIntegrityService auditIntegrityService) {
        this.auditIntegrityService = auditIntegrityService;
    }

    @Scheduled(
            fixedDelayString = "${app.audit.integrity.verification-interval-ms:300000}",
            initialDelayString = "${app.audit.integrity.verification-initial-delay-ms:60000}"
    )
    public void verifyLatestWindow() {
        try {
            AuditIntegrityResponse response = auditIntegrityService.verifyScheduled(SOURCE_SERVICE, WINDOW_LIMIT);
            if ("INVALID".equals(response.status())) {
                log.error(
                        "Audit integrity violation detected status={} checked={} limit={} partition_key={} violations={}",
                        response.status(),
                        response.checked(),
                        response.limit(),
                        response.partitionKey(),
                        response.violations().stream().map(AuditIntegrityViolation::violationType).distinct().toList()
                );
            } else if ("UNAVAILABLE".equals(response.status())) {
                log.error("Audit integrity verification unavailable checked={} limit={}", response.checked(), response.limit());
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Audit integrity scheduled verification failed without repair error_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
