package com.frauddetection.alert.security.config;

import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessAuditFailureInterceptor;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessAuditWebConfig;
import com.frauddetection.alert.audit.read.ReadAccessResultCountExtractor;
import com.frauddetection.alert.audit.read.SensitiveReadAuditPolicy;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Strict sensitive-read audit wiring for JWT MVC slices, with only persistence mocked. */
@TestConfiguration(proxyBeanMethods = false)
@Import({
        ReadAccessAuditClassifier.class,
        ReadAccessResultCountExtractor.class,
        ReadAccessAuditService.class,
        SensitiveReadAuditPolicy.class,
        ReadAccessAuditFailureInterceptor.class,
        ReadAccessAuditWebConfig.class,
        CurrentAnalystUser.class
})
class JwtAlertReadAuditSliceTestConfiguration {
}
