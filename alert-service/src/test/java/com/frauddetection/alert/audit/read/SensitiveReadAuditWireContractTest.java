package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(
        classes = SensitiveReadAuditWireContractTest.WireTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.sensitive-reads.audit.fail-closed=true"
)
class SensitiveReadAuditWireContractTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"
    })
    @Import({WireTestController.class, ReadAccessAuditClassifier.class, ReadAccessResultCountExtractor.class,
            ReadAccessAuditService.class,
            ReadAccessAuditResponseAdvice.class, ReadAccessAuditFailureInterceptor.class,
            ReadAccessAuditWebConfig.class, SensitiveReadAuditPolicy.class})
    static class WireTestApplication { }

    @RestController
    static class WireTestController {
        @GetMapping("/wire/body")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ,
                resourceType = ReadAccessResourceType.SUSPICIOUS_TRANSACTION)
        String body() {
            return "sensitive-wire-payload";
        }

        @GetMapping("/wire/no-content")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SUMMARY,
                resourceType = ReadAccessResourceType.SUSPICIOUS_TRANSACTION)
        ResponseEntity<Void> noContent() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/wire/not-modified")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SUMMARY,
                resourceType = ReadAccessResourceType.SUSPICIOUS_TRANSACTION)
        ResponseEntity<Void> notModified() {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag("\"audit-etag\"").build();
        }

        @GetMapping("/wire/stream")
        @AuditedSensitiveRead(category = ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_READ,
                resourceType = ReadAccessResourceType.SUSPICIOUS_TRANSACTION)
        StreamingResponseBody stream() {
            return output -> output.write("sensitive-stream-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private ReadAccessAuditRepository repository;

    @MockitoBean
    private CurrentAnalystUser currentAnalystUser;

    @MockitoBean
    private AlertServiceMetrics metrics;

    @Autowired
    private SensitiveReadAuditPolicy policy;

    @BeforeEach
    void setUp() {
        reset(repository, currentAnalystUser, metrics);
        assertThat(policy.failClosed()).isTrue();
        org.mockito.Mockito.when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "wire-test-actor", java.util.Set.of(AnalystRole.ANALYST), AnalystRole.ANALYST.authorities(),
                "https://issuer.example#wire-test-actor")));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit database unavailable"))
                .when(repository).save(any(ReadAccessAuditEventDocument.class));
    }

    @Test
    void auditFailureSuppressesSensitiveJsonOnTheWire() {
        var response = get("/wire/body");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.body()).contains("Sensitive read audit unavailable.")
                .doesNotContain("sensitive-wire-payload", "audit database unavailable");
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void auditFailureConverts204ToUnavailableBeforeTheResponseIsCommitted() {
        var response = get("/wire/no-content");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.body()).contains("Sensitive read audit unavailable.");
    }

    @Test
    void auditFailureConverts304ToUnavailableWithAProtocolValidBody() {
        var response = get("/wire/not-modified");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.body()).contains("Sensitive read audit unavailable.");
        assertThat(response.headers().firstValue(HttpHeaders.ETAG)).isEmpty();
    }

    @Test
    void successful204And304PreserveProtocolStatusAfterAuditPersistence() {
        org.mockito.Mockito.when(repository.save(any(ReadAccessAuditEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var noContent = get("/wire/no-content");
        var notModified = get("/wire/not-modified");

        assertThat(noContent.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(noContent.body()).isEmpty();
        assertThat(notModified.statusCode()).isEqualTo(HttpStatus.NOT_MODIFIED.value());
        assertThat(notModified.body()).isEmpty();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.argThat(event ->
                        event.endpointCategory() == ReadAccessEndpointCategory.SUSPICIOUS_TRANSACTION_SUMMARY
                                && event.outcome() == ReadAccessAuditOutcome.SUCCESS));
    }

    @Test
    void streamingReadIsRejectedBeforeHandlerStarts() {
        var response = get("/wire/stream");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.body()).contains("Sensitive read audit unavailable.")
                .doesNotContain("sensitive-stream-payload");
    }

    private java.net.http.HttpResponse<String> get(String path) {
        try {
            return java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not call embedded HTTP server", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during embedded HTTP request", exception);
        }
    }
}
