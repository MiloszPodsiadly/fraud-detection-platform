package com.frauddetection.alert.audit.read;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ReadAccessAuditFailureInterceptor implements HandlerInterceptor {

    private final ObjectProvider<ReadAccessAuditClassifier> classifier;
    private final ObjectProvider<ReadAccessAuditService> auditService;
    private final ObjectProvider<SensitiveReadAuditPolicy> auditPolicy;

    public ReadAccessAuditFailureInterceptor(
            ObjectProvider<ReadAccessAuditClassifier> classifier,
            ObjectProvider<ReadAccessAuditService> auditService,
            ObjectProvider<SensitiveReadAuditPolicy> auditPolicy
    ) {
        this.classifier = classifier;
        this.auditService = auditService;
        this.auditPolicy = auditPolicy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!failClosed() || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        ReadAccessAuditClassifier routeClassifier = classifier.getIfAvailable();
        boolean declaredSensitive = ReadAccessAuditClassifier.isSensitiveReadHandler(handlerMethod);
        if (routeClassifier == null && declaredSensitive) {
            ReadAccessAuditResponseAdvice.markAttempted(request);
            unavailable(response);
            return false;
        }
        boolean sensitive = routeClassifier != null && routeClassifier.classify(request).isPresent();
        if (!sensitive) {
            return true;
        }
        if (!ReadAccessAuditResponseAdvice.unsupportedDeclaredResponse(
                new org.springframework.core.MethodParameter(handlerMethod.getMethod(), -1))) {
            return true;
        }
        ReadAccessAuditResponseAdvice.markAttempted(request);
        ReadAccessAuditService auditor = auditService.getIfAvailable();
        var target = routeClassifier.classify(request);
        if (auditor != null && target.isPresent()) {
            try {
                auditor.auditOrThrow(target.get(), ReadAccessAuditOutcome.FAILED,
                        0, request.getHeader("X-Correlation-Id"));
            } catch (RuntimeException ignored) {
                // The bounded 503 is the same whether this unsupported read could be recorded or not.
            }
        }
        unavailable(response);
        return false;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        if (ReadAccessAuditResponseAdvice.wasAttempted(request)) {
            return;
        }
        ReadAccessAuditClassifier auditClassifier = classifier.getIfAvailable();
        ReadAccessAuditService auditor = auditService.getIfAvailable();
        if (auditClassifier == null) {
            return;
        }
        var target = auditClassifier.classify(request);
        if (target.isEmpty()) {
            return;
        }
        boolean failClosed = failClosed();
        boolean successful = response.getStatus() >= 200 && response.getStatus() < 300
                || response.getStatus() == HttpStatus.NOT_MODIFIED.value();
        if (!successful && exception == null && response.getStatus() < 400) {
            return;
        }
        ReadAccessAuditResponseAdvice.markAttempted(request);
        if (auditor == null) {
            if (failClosed && successful) {
                unavailable(response);
            }
            return;
        }
        ReadAccessAuditOutcome outcome = response.getStatus() >= 400 && response.getStatus() < 500
                ? ReadAccessAuditOutcome.REJECTED
                : successful ? ReadAccessAuditOutcome.SUCCESS : ReadAccessAuditOutcome.FAILED;
        try {
            if (failClosed) {
                auditor.auditOrThrow(target.get(), outcome,
                        0, request.getHeader("X-Correlation-Id"));
            } else {
                auditor.audit(target.get(), outcome, 0, request.getHeader("X-Correlation-Id"));
            }
        } catch (RuntimeException auditFailure) {
            if (failClosed && successful) {
                unavailable(response);
            }
        }
    }

    private boolean failClosed() {
        SensitiveReadAuditPolicy policy = auditPolicy.getIfAvailable();
        return policy == null || policy.failClosed();
    }

    private void unavailable(HttpServletResponse response) {
        ReadAccessAuditResponseAdvice.writeUnavailableResponse(response);
    }
}
