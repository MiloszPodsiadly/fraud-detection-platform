package com.frauddetection.alert.audit.read;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@ControllerAdvice
public class ReadAccessAuditResponseAdvice implements ResponseBodyAdvice<Object> {

    static final String AUDITED_ATTRIBUTE = ReadAccessAuditResponseAdvice.class.getName() + ".audited";

    private final ObjectProvider<ReadAccessAuditClassifier> classifier;
    private final ObjectProvider<ReadAccessResultCountExtractor> resultCountExtractor;
    private final ObjectProvider<ReadAccessAuditService> auditService;
    private final ObjectProvider<SensitiveReadAuditPolicy> auditPolicy;

    public ReadAccessAuditResponseAdvice(
            ObjectProvider<ReadAccessAuditClassifier> classifier,
            ObjectProvider<ReadAccessResultCountExtractor> resultCountExtractor,
            ObjectProvider<ReadAccessAuditService> auditService,
            ObjectProvider<SensitiveReadAuditPolicy> auditPolicy
    ) {
        this.classifier = classifier;
        this.resultCountExtractor = resultCountExtractor;
        this.auditService = auditService;
        this.auditPolicy = auditPolicy;
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
            if (wasAttempted(httpRequest)) {
                return body;
            }
            ReadAccessAuditClassifier auditClassifier = classifier.getIfAvailable();
            if (auditClassifier == null) {
                Object handler = httpRequest.getAttribute(org.springframework.web.servlet.HandlerMapping
                        .BEST_MATCHING_HANDLER_ATTRIBUTE);
                if (failClosed() && handler instanceof HandlerMethod handlerMethod
                        && ReadAccessAuditClassifier.isSensitiveReadHandler(handlerMethod)) {
                    markAttempted(httpRequest);
                    throw unavailable();
                }
                return body;
            }
            var target = auditClassifier.classify(httpRequest);
            if (target.isEmpty()) {
                return body;
            }
            boolean failClosed = failClosed();
            ReadAccessAuditService auditor = auditService.getIfAvailable();
            ReadAccessResultCountExtractor countExtractor = resultCountExtractor.getIfAvailable();
            HttpServletResponse httpResponse = response instanceof ServletServerHttpResponse servletResponse
                    ? servletResponse.getServletResponse()
                    : null;
            int status = httpResponse == null ? 200 : httpResponse.getStatus();
            if (!successfulReadStatus(status)) {
                return body;
            }
            markAttempted(httpRequest);
            if (auditor == null || countExtractor == null) {
                if (failClosed) {
                    if (httpResponse == null) {
                        throw unavailable();
                    }
                    writeUnavailableResponse(httpResponse);
                    return null;
                }
                return body;
            }
            if (failClosed && unsupportedResponse(body, returnType)) {
                try {
                    auditor.auditOrThrow(target.get(), ReadAccessAuditOutcome.FAILED, 0,
                            httpRequest.getHeader("X-Correlation-Id"));
                } catch (RuntimeException ignored) {
                    // The response remains unavailable; the audit service already records the failure.
                }
                if (httpResponse == null) {
                    throw unavailable();
                }
                writeUnavailableResponse(httpResponse);
                return null;
            }
            try {
                if (failClosed) {
                    auditor.auditOrThrow(target.get(), ReadAccessAuditOutcome.SUCCESS,
                            countExtractor.resultCount(body, target.get().endpointCategory()),
                            httpRequest.getHeader("X-Correlation-Id"));
                } else {
                    auditor.audit(target.get(), ReadAccessAuditOutcome.SUCCESS,
                            countExtractor.resultCount(body, target.get().endpointCategory()),
                            httpRequest.getHeader("X-Correlation-Id"));
                }
            } catch (RuntimeException exception) {
                if (failClosed) {
                    if (httpResponse != null) {
                        writeUnavailableResponse(httpResponse);
                        return null;
                    }
                    throw unavailable();
                }
            }
        }
        return body;
    }

    static boolean wasAttempted(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(AUDITED_ATTRIBUTE));
    }

    static void markAttempted(HttpServletRequest request) {
        request.setAttribute(AUDITED_ATTRIBUTE, Boolean.TRUE);
    }

    static void writeUnavailableResponse(HttpServletResponse response) {
        if (response == null || response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Content-Length", null);
        response.setHeader("Content-Encoding", null);
        response.setHeader("ETag", null);
        response.setHeader("Last-Modified", null);
        response.setHeader("Content-Range", null);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            response.getWriter().write("{\"status\":503,\"error\":\"Service Unavailable\","
                    + "\"message\":\"Sensitive read audit unavailable.\",\"details\":[]}");
        } catch (IOException ignored) {
            response.setContentLength(0);
        }
    }

    private boolean failClosed() {
        SensitiveReadAuditPolicy policy = auditPolicy.getIfAvailable();
        return policy == null || policy.failClosed();
    }

    private boolean successfulReadStatus(int status) {
        return status >= 200 && status < 300 || status == HttpServletResponse.SC_NOT_MODIFIED;
    }

    private boolean unsupportedResponse(Object body, MethodParameter returnType) {
        if (body instanceof StreamingResponseBody || body instanceof ResponseBodyEmitter
                || body instanceof SseEmitter || body instanceof InputStream
                || body instanceof Resource
                || body instanceof Callable<?> || body instanceof DeferredResult<?>
                || body instanceof WebAsyncTask<?> || body instanceof CompletionStage<?>
                || body instanceof java.util.concurrent.Flow.Publisher<?>
                || body instanceof org.reactivestreams.Publisher<?>) {
            return true;
        }
        if (returnType == null) {
            return false;
        }
        return unsupportedType(returnType.getGenericParameterType());
    }

    static boolean unsupportedDeclaredResponse(MethodParameter returnType) {
        return returnType != null && unsupportedType(returnType.getGenericParameterType());
    }

    private static boolean unsupportedType(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            if (unsupportedType(parameterizedType.getRawType())) {
                return true;
            }
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                if (unsupportedType(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (!(type instanceof Class<?> declaredType)) {
            return false;
        }
        return StreamingResponseBody.class.isAssignableFrom(declaredType)
                || ResponseBodyEmitter.class.isAssignableFrom(declaredType)
                || SseEmitter.class.isAssignableFrom(declaredType)
                || InputStream.class.isAssignableFrom(declaredType)
                || Resource.class.isAssignableFrom(declaredType)
                || Callable.class.isAssignableFrom(declaredType)
                || DeferredResult.class.isAssignableFrom(declaredType)
                || WebAsyncTask.class.isAssignableFrom(declaredType)
                || CompletionStage.class.isAssignableFrom(declaredType)
                || java.util.concurrent.Flow.Publisher.class.isAssignableFrom(declaredType)
                || org.reactivestreams.Publisher.class.isAssignableFrom(declaredType);
    }

    private org.springframework.web.server.ResponseStatusException unavailable() {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Sensitive read audit unavailable.");
    }
}
