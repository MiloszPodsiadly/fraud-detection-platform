package com.frauddetection.alert.audit.read;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Explicit best-effort policy for MVC slices that intentionally mock audit persistence. */
@TestConfiguration(proxyBeanMethods = false)
public class SensitiveReadAuditSliceTestConfiguration {

    @Bean
    SensitiveReadAuditPolicy sensitiveReadAuditSliceTestPolicy(Environment environment) {
        return new SensitiveReadAuditPolicy(environment, false, false);
    }
}
