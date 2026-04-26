package com.frauddetection.alert.audit.read;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ReadAccessAuditFailureInterceptor implements HandlerInterceptor {

    private final ObjectProvider<ReadAccessAuditClassifier> classifier;
    private final ObjectProvider<ReadAccessAuditService> auditService;

    public ReadAccessAuditFailureInterceptor(
            ObjectProvider<ReadAccessAuditClassifier> classifier,
            ObjectProvider<ReadAccessAuditService> auditService
    ) {
        this.classifier = classifier;
        this.auditService = auditService;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        if (Boolean.TRUE.equals(request.getAttribute(ReadAccessAuditResponseAdvice.AUDITED_ATTRIBUTE))) {
            return;
        }
        if (exception == null && response.getStatus() < 400) {
            return;
        }
        ReadAccessAuditClassifier auditClassifier = classifier.getIfAvailable();
        ReadAccessAuditService auditor = auditService.getIfAvailable();
        if (auditClassifier == null || auditor == null) {
            return;
        }
        auditClassifier.classify(request).ifPresent(target -> auditor.audit(
                target,
                ReadAccessAuditOutcome.FAILED,
                0,
                request.getHeader("X-Correlation-Id")
        ));
    }
}
