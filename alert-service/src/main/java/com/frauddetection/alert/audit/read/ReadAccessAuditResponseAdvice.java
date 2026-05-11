package com.frauddetection.alert.audit.read;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class ReadAccessAuditResponseAdvice implements ResponseBodyAdvice<Object> {

    static final String AUDITED_ATTRIBUTE = ReadAccessAuditResponseAdvice.class.getName() + ".audited";

    private final ObjectProvider<ReadAccessAuditClassifier> classifier;
    private final ObjectProvider<ReadAccessResultCountExtractor> resultCountExtractor;
    private final ObjectProvider<ReadAccessAuditService> auditService;

    public ReadAccessAuditResponseAdvice(
            ObjectProvider<ReadAccessAuditClassifier> classifier,
            ObjectProvider<ReadAccessResultCountExtractor> resultCountExtractor,
            ObjectProvider<ReadAccessAuditService> auditService
    ) {
        this.classifier = classifier;
        this.resultCountExtractor = resultCountExtractor;
        this.auditService = auditService;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            if (Boolean.TRUE.equals(httpRequest.getAttribute(AUDITED_ATTRIBUTE))) {
                return body;
            }
            ReadAccessAuditClassifier auditClassifier = classifier.getIfAvailable();
            ReadAccessResultCountExtractor countExtractor = resultCountExtractor.getIfAvailable();
            ReadAccessAuditService auditor = auditService.getIfAvailable();
            if (auditClassifier == null || countExtractor == null || auditor == null) {
                return body;
            }
            HttpServletResponse httpResponse = response instanceof ServletServerHttpResponse servletResponse
                    ? servletResponse.getServletResponse()
                    : null;
            auditClassifier.classify(httpRequest).ifPresent(target -> {
                int status = httpResponse == null ? 200 : httpResponse.getStatus();
                if (status < 400) {
                    auditor.audit(
                            target,
                            ReadAccessAuditOutcome.SUCCESS,
                            countExtractor.resultCount(body, target.endpointCategory()),
                            httpRequest.getHeader("X-Correlation-Id")
                    );
                    httpRequest.setAttribute(AUDITED_ATTRIBUTE, Boolean.TRUE);
                }
            });
        }
        return body;
    }
}
