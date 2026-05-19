package com.frauddetection.alert.security.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessHandlerTelemetryFailureLogTest {

    @Test
    void authenticationEntryPointTelemetryFailureLogIsBounded() throws Exception {
        try (LogCapture logs = LogCapture.start(ApiAuthenticationEntryPoint.class)) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "GET",
                    "/internal/suspicious-transactions/suspicious-secret-123"
            );
            request.setQueryString("cursor=cursor-secret&customerId=customer-secret");
            request.addHeader("Authorization", "Bearer secret-token-value");
            MockHttpServletResponse response = new MockHttpServletResponse();

            entryPoint(throwingRecorder()).commence(
                    request,
                    response,
                    new InsufficientAuthenticationException("raw unauthorized exception customer-secret")
            );

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(logs.messages())
                    .contains(
                            "Security denied-access telemetry failed",
                            "outcome=unauthorized",
                            "routeGroup=suspicious_transaction_read",
                            "method=GET",
                            "authState=anonymous"
                    )
                    .doesNotContain(
                            "suspicious-secret-123",
                            "cursor-secret",
                            "customer-secret",
                            "Authorization",
                            "secret-token-value",
                            "raw unauthorized exception",
                            "IllegalStateException"
                    );
            assertThat(logs.hasThrowable()).isFalse();
        }
    }

    @Test
    void accessDeniedHandlerTelemetryFailureLogIsBounded() throws Exception {
        try (LogCapture logs = LogCapture.start(ApiAccessDeniedHandler.class)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "m.podsiadly99@gmail.com",
                    null,
                    List.of(new SimpleGrantedAuthority("alert:read"))
            ));
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "GET",
                    "/internal/suspicious-transactions/suspicious-secret-123"
            );
            request.setQueryString("cursor=cursor-secret");
            request.addHeader("Authorization", "Bearer secret-token-value");
            MockHttpServletResponse response = new MockHttpServletResponse();

            handler(throwingRecorder()).handle(
                    request,
                    response,
                    new AccessDeniedException("raw forbidden exception cursor-secret")
            );
            SecurityContextHolder.clearContext();

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(logs.messages())
                    .contains(
                            "Security denied-access telemetry failed",
                            "outcome=forbidden",
                            "routeGroup=suspicious_transaction_read",
                            "method=GET",
                            "authState=authenticated"
                    )
                    .doesNotContain(
                            "suspicious-secret-123",
                            "cursor-secret",
                            "m.podsiadly99@gmail.com",
                            "Authorization",
                            "secret-token-value",
                            "raw forbidden exception",
                            "IllegalStateException"
                    );
            assertThat(logs.hasThrowable()).isFalse();
        }
    }

    private SecurityDeniedAccessTelemetryRecorder throwingRecorder() {
        return new SecurityDeniedAccessTelemetryRecorder(new SimpleMeterRegistry()) {
            @Override
            public void record(SecurityDeniedAccessSnapshot snapshot) {
                throw new IllegalStateException("raw recorder exception customer-secret cursor-secret token-secret");
            }
        };
    }

    private ApiAuthenticationEntryPoint entryPoint(SecurityDeniedAccessTelemetryRecorder recorder) {
        return new ApiAuthenticationEntryPoint(
                new SecurityErrorResponseWriter(new ObjectMapper().findAndRegisterModules()),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                recorder,
                new SecurityDeniedAccessRouteClassifier(),
                new SecurityDeniedAccessMethodClassifier(),
                new SecurityDeniedAccessAuthStateClassifier()
        );
    }

    private ApiAccessDeniedHandler handler(SecurityDeniedAccessTelemetryRecorder recorder) {
        return new ApiAccessDeniedHandler(
                new SecurityErrorResponseWriter(new ObjectMapper().findAndRegisterModules()),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                recorder,
                new SecurityDeniedAccessRouteClassifier(),
                new SecurityDeniedAccessMethodClassifier(),
                new SecurityDeniedAccessAuthStateClassifier()
        );
    }

    private static final class LogCapture implements AutoCloseable {
        private final Logger logger;
        private final Level previousLevel;
        private final boolean previousAdditive;
        private final ListAppender<ILoggingEvent> appender;

        private LogCapture(Logger logger, Level previousLevel, boolean previousAdditive, ListAppender<ILoggingEvent> appender) {
            this.logger = logger;
            this.previousLevel = previousLevel;
            this.previousAdditive = previousAdditive;
            this.appender = appender;
        }

        static LogCapture start(Class<?> loggerClass) {
            Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
            Level previousLevel = logger.getLevel();
            boolean previousAdditive = logger.isAdditive();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.setAdditive(false);
            logger.addAppender(appender);
            return new LogCapture(logger, previousLevel, previousAdditive, appender);
        }

        String messages() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
        }

        boolean hasThrowable() {
            return appender.list.stream().anyMatch(event -> event.getThrowableProxy() != null);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }
}
