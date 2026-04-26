package com.frauddetection.alert.audit.read;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ReadAccessAuditWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<ReadAccessAuditFailureInterceptor> failureInterceptor;

    public ReadAccessAuditWebConfig(ObjectProvider<ReadAccessAuditFailureInterceptor> failureInterceptor) {
        this.failureInterceptor = failureInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        ReadAccessAuditFailureInterceptor interceptor = failureInterceptor.getIfAvailable();
        if (interceptor != null) {
            registry.addInterceptor(interceptor);
        }
    }
}
