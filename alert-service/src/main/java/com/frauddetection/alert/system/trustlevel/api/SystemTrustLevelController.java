package com.frauddetection.alert.system.trustlevel.api;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.system.trustlevel.application.SystemTrustLevelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemTrustLevelController {

    private final SystemTrustLevelService trustLevelService;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    @Autowired
    public SystemTrustLevelController(
            SystemTrustLevelService trustLevelService,
            ObjectProvider<SensitiveReadAuditService> sensitiveReadAuditService
    ) {
        this(
                trustLevelService,
                sensitiveReadAuditService == null ? null : sensitiveReadAuditService.getIfAvailable()
        );
    }

    public SystemTrustLevelController(
            SystemTrustLevelService trustLevelService,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.trustLevelService = trustLevelService;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @GetMapping("/system/trust-level")
    @AuditedSensitiveRead
    public SystemTrustLevelResponse trustLevel(HttpServletRequest request) {
        SystemTrustLevelResponse response = trustLevelService.trustLevel();
        if (sensitiveReadAuditService != null) {
            sensitiveReadAuditService.audit(
                    ReadAccessEndpointCategory.SYSTEM_TRUST_LEVEL,
                    ReadAccessResourceType.SYSTEM_TRUST_LEVEL,
                    null,
                    1,
                    request
            );
        }
        return response;
    }

    public SystemTrustLevelResponse trustLevel() {
        return trustLevel(null);
    }
}
