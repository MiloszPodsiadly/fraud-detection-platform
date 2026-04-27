package com.frauddetection.scoring.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;

import javax.net.ssl.SSLHandshakeException;
import java.text.ParseException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.cert.CertificateExpiredException;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalServiceClientProdGuardTest {

    @Test
    void shouldFailClosedWhenProdProfileDisablesInternalAuthClient() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                disabledProperties(),
                environment("prod")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal service auth client must be enabled in prod-like profiles.");
    }

    @Test
    void shouldFailClosedWhenProdProfileMissesToken() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(true, "TOKEN_VALIDATOR", "fraud-scoring-service", " ", false, InternalServiceClientProperties.Jwt.empty(), InternalServiceClientProperties.Mtls.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.token is required when TOKEN_VALIDATOR is enabled")
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void shouldRequireTokenValidatorCompatibilityOptInForProdProfile() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                new InternalServiceClientProperties(true, "TOKEN_VALIDATOR", "fraud-scoring-service", "token", false, InternalServiceClientProperties.Jwt.empty(), InternalServiceClientProperties.Mtls.empty()),
                environment("prod")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TOKEN_VALIDATOR internal auth client mode requires explicit prod compatibility opt-in.")
                .hasMessageNotContaining("token");
    }

    @Test
    void shouldAllowCompleteJwtServiceIdentityConfigForProdProfile() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                jwtProperties(),
                environment("staging")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void shouldAttachJwtAuthorizationHeaderWithoutSharedSecretHeaders() throws ParseException {
        HttpHeaders headers = new HttpHeaders();

        new InternalServiceAuthHeaders(jwtProperties()).apply(headers);

        assertThat(headers.getFirst("Authorization")).startsWith("Bearer ");
        assertThat(headers.containsKey("X-Internal-Service-Name")).isFalse();
        assertThat(headers.containsKey("X-Internal-Service-Token")).isFalse();
        String token = headers.getFirst("Authorization").substring("Bearer ".length());
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("scoring-key-1");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("fraud-platform-local");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("ml-inference-service");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("service_name")).isEqualTo("fraud-scoring-service");
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("authorities")).containsExactly("ml-score");
        assertThat(jwt.getJWTClaimsSet().getIssueTime()).isNotNull();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isAfter(jwt.getJWTClaimsSet().getIssueTime());
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isBeforeOrEqualTo(
                Date.from(jwt.getJWTClaimsSet().getIssueTime().toInstant().plus(Duration.ofMinutes(5).plusSeconds(1)))
        );
    }

    @Test
    void shouldRejectHs256JwtServiceIdentityForProdProfile() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                hs256JwtProperties(),
                environment("prod")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HS256 internal auth client JWT mode is local compatibility only and is forbidden in prod-like profiles.");
    }

    @Test
    void shouldRequireRs256KidAndPrivateKey() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(
                true,
                "JWT_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "RS256",
                        "fraud-platform-local",
                        "ml-inference-service",
                        "",
                        "",
                        "unused-test-private-key",
                        "",
                        Duration.ofMinutes(5),
                        "ml-score"
                ),
                InternalServiceClientProperties.Mtls.empty()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.jwt issuer, audience, algorithm, key material, ttl, and authorities are required when JWT_SERVICE_IDENTITY is enabled");
    }

    @Test
    void shouldRequireRs256PrivateKeyMaterial() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(
                true,
                "JWT_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "RS256",
                        "fraud-platform-local",
                        "ml-inference-service",
                        "",
                        "scoring-key-1",
                        "",
                        "",
                        Duration.ofMinutes(5),
                        "ml-score"
                ),
                InternalServiceClientProperties.Mtls.empty()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.jwt issuer, audience, algorithm, key material, ttl, and authorities are required when JWT_SERVICE_IDENTITY is enabled");
    }

    @Test
    void shouldFailCleanlyWhenRs256PrivateKeyIsInvalid() {
        HttpHeaders headers = new HttpHeaders();

        assertThatThrownBy(() -> new InternalServiceAuthHeaders(invalidPrivateKeyJwtProperties()).apply(headers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal JWT signing is not available.")
                .hasMessageNotContaining("not-a-private-key")
                .hasMessageNotContaining("scoring-key-1");
    }

    @Test
    void shouldAllowDockerProfileWithExplicitLocalConfiguration() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                disabledProperties(),
                environment("docker")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void shouldAllowCompleteMtlsServiceIdentityConfigForProdProfile() {
        InternalServiceClientProdGuard guard = new InternalServiceClientProdGuard(
                mtlsProperties(),
                environment("prod")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectIncompleteMtlsServiceIdentityConfig() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(
                true,
                "MTLS_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                InternalServiceClientProperties.Jwt.empty(),
                InternalServiceClientProperties.Mtls.empty()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.mtls client certificate, private key, CA, and expected server identity are required when MTLS_SERVICE_IDENTITY is enabled");
    }

    @Test
    void shouldRejectMtlsConfigurationWithoutCaMaterial() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(
                true,
                "MTLS_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                InternalServiceClientProperties.Jwt.empty(),
                new InternalServiceClientProperties.Mtls(
                        "client.pem",
                        "client-key.pem",
                        " ",
                        "ml-inference-service",
                        false
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.mtls client certificate, private key, CA, and expected server identity are required when MTLS_SERVICE_IDENTITY is enabled");
    }

    @Test
    void shouldRejectMtlsConfigurationWithoutClientPrivateKey() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(
                true,
                "MTLS_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                InternalServiceClientProperties.Jwt.empty(),
                new InternalServiceClientProperties.Mtls(
                        "client.pem",
                        " ",
                        "ca.pem",
                        "ml-inference-service",
                        false
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.mtls client certificate, private key, CA, and expected server identity are required when MTLS_SERVICE_IDENTITY is enabled");
    }

    @Test
    void shouldRejectMtlsTrustAllMode() {
        assertThatThrownBy(() -> new InternalServiceClientProperties(
                true,
                "MTLS_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                InternalServiceClientProperties.Jwt.empty(),
                new InternalServiceClientProperties.Mtls(
                        "client.pem",
                        "client-key.pem",
                        "ca.pem",
                        "ml-inference-service",
                        true
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.internal-auth.client.mtls trust-all is not allowed");
    }

    @Test
    void shouldNotInstallCustomHostnameVerifierOrTrustAllSslContext() throws IOException {
        String source = Files.readString(repoPath("fraud-scoring-service/src/main/java/com/frauddetection/scoring/config/InternalServiceClientRequestFactory.java"));

        assertThat(source)
                .doesNotContain("setHostnameVerifier")
                .doesNotContain("HostnameVerifier")
                .doesNotContain("NoopHostnameVerifier")
                .doesNotContain("TrustAllStrategy")
                .doesNotContain("X509TrustManager")
                .doesNotContain("trustAll");
    }

    @Test
    void shouldNotAttachHeaderIdentityWhenMtlsModeIsEnabled() {
        HttpHeaders headers = new HttpHeaders();

        new InternalServiceAuthHeaders(mtlsProperties()).apply(headers);

        assertThat(headers.containsKey("Authorization")).isFalse();
        assertThat(headers.containsKey("X-Internal-Service-Name")).isFalse();
        assertThat(headers.containsKey("X-Internal-Service-Token")).isFalse();
    }

    @Test
    void shouldRejectMismatchedMtlsExpectedServerIdentityBeforeCreatingClient() {
        assertThatThrownBy(() -> InternalServiceClientRequestFactory.create(
                URI.create("https://wrong-service:8090"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                mtlsProperties()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal mTLS expected server identity does not match target host.");
    }

    @Test
    void shouldExposeMtlsCertificateExpiryAndAgeMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalMtlsCertificateMonitor monitor = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificate("deployment/service-identity/mtls/fraud-scoring-service.pem"),
                registry
        );

        monitor.afterPropertiesSet();

        assertThat(registry.get("fraud_internal_mtls_cert_expiry_seconds")
                .tag("source_service", "fraud-scoring-service")
                .tag("target_service", "ml-inference-service")
                .gauge()
                .value()).isGreaterThan(0);
        assertThat(registry.get("fraud_internal_mtls_cert_age_seconds")
                .tag("source_service", "fraud-scoring-service")
                .tag("target_service", "ml-inference-service")
                .gauge()
                .value()).isGreaterThan(0);
    }

    @Test
    void shouldFailStartupWhenMtlsCertificateIsExpired() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalMtlsCertificateMonitor monitor = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificate("deployment/service-identity/mtls/expired-service.pem"),
                registry
        );

        assertThatThrownBy(monitor::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal mTLS client certificate is expired.");
    }

    @Test
    void shouldReportWarnHealthWhenMtlsCertificateExpiresSoon() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Instant now = Instant.parse("2026-04-27T12:00:00Z");
        InternalMtlsCertificateMonitor monitor = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificate("deployment/service-identity/mtls/fraud-scoring-service.pem"),
                registry,
                Clock.fixed(now, ZoneOffset.UTC),
                () -> new InternalMtlsCertificateMonitor.CertificateWindow(
                        Date.from(now.minus(Duration.ofHours(1))),
                        Date.from(now.plus(Duration.ofDays(5)))
                )
        );

        monitor.afterPropertiesSet();

        assertThat(monitor.health().getStatus().getCode()).isEqualTo("WARN");
    }

    @Test
    void shouldReportCriticalHealthWhenMtlsCertificateExpiresWithinTwentyFourHours() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Instant now = Instant.parse("2026-04-27T12:00:00Z");
        InternalMtlsCertificateMonitor monitor = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificate("deployment/service-identity/mtls/fraud-scoring-service.pem"),
                registry,
                Clock.fixed(now, ZoneOffset.UTC),
                () -> new InternalMtlsCertificateMonitor.CertificateWindow(
                        Date.from(now.minus(Duration.ofHours(1))),
                        Date.from(now.plus(Duration.ofHours(12)))
                )
        );

        monitor.afterPropertiesSet();

        assertThat(monitor.health().getStatus().getCode()).isEqualTo("CRITICAL");
        assertThat(registry.get("fraud_internal_mtls_cert_expiry_state_total")
                .tag("state", "CRITICAL")
                .counter()
                .count()).isGreaterThan(0);
    }

    @Test
    void shouldFailStartupWhenMtlsCertificateIsRemoved() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalMtlsCertificateMonitor monitor = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificate("deployment/service-identity/mtls/missing-service.pem"),
                registry
        );

        assertThatThrownBy(monitor::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal mTLS certificate trust material is unavailable or invalid.");
    }

    @Test
    void shouldFailStartupWhenMtlsTrustChainIsBroken() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalMtlsCertificateMonitor monitor = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificateAndCa(
                        "deployment/service-identity/mtls/fraud-scoring-service.pem",
                        "deployment/service-identity/mtls/unknown-service.pem"
                ),
                registry
        );

        assertThatThrownBy(monitor::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal mTLS client certificate is not trusted by configured CA material.");
    }

    @Test
    void shouldAllowRotationOverlapAndRejectOldTrustAfterRemoval() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalMtlsCertificateMonitor overlap = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificateAndCa(
                        "deployment/service-identity/mtls/fraud-scoring-service.pem",
                        "deployment/service-identity/mtls/unknown-service.pem,deployment/service-identity/mtls/local-dev-ca.pem"
                ),
                registry
        );
        InternalMtlsCertificateMonitor oldTrustOnly = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificateAndCa(
                        "deployment/service-identity/mtls/fraud-scoring-service.pem",
                        "deployment/service-identity/mtls/unknown-service.pem"
                ),
                registry
        );

        assertThatCode(overlap::afterPropertiesSet).doesNotThrowAnyException();
        assertThatThrownBy(oldTrustOnly::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal mTLS client certificate is not trusted by configured CA material.");
    }

    @Test
    void shouldRefreshLifecycleMetricsDuringPeriodicMonitor() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InternalMtlsCertificateMonitor monitor = new InternalMtlsCertificateMonitor(
                mtlsPropertiesWithCertificate("deployment/service-identity/mtls/fraud-scoring-service.pem"),
                registry
        );

        monitor.monitorLifecycle();

        assertThat(registry.get("fraud_internal_mtls_cert_expiry_seconds")
                .tag("source_service", "fraud-scoring-service")
                .tag("target_service", "ml-inference-service")
                .gauge()
                .value()).isGreaterThan(0);
        assertThat(registry.get("fraud_internal_mtls_cert_expiry_state_total")
                .tag("state", "UP")
                .counter()
                .count()).isGreaterThan(0);
    }

    @Test
    void shouldClassifyMtlsHandshakeFailuresWithBoundedReasons() {
        assertThat(InternalMtlsClientHandshakeMetrics.reason(
                new RuntimeException(new CertificateExpiredException())
        )).isEqualTo("EXPIRED_CERT");
        assertThat(InternalMtlsClientHandshakeMetrics.reason(
                new RuntimeException(new SSLHandshakeException("No name matching ml-inference-service found"))
        )).isEqualTo("HOSTNAME_MISMATCH");
        assertThat(InternalMtlsClientHandshakeMetrics.reason(
                new RuntimeException(new SSLHandshakeException("PKIX path building failed"))
        )).isEqualTo("UNTRUSTED_CA");
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private InternalServiceClientProperties jwtProperties() {
        return new InternalServiceClientProperties(
                true,
                "JWT_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "RS256",
                        "fraud-platform-local",
                        "ml-inference-service",
                        "local-dev-jwt-service-secret-32bytes",
                        "scoring-key-1",
                        privateKeyPem(),
                        "",
                        Duration.ofMinutes(5),
                        "ml-score"
                ),
                InternalServiceClientProperties.Mtls.empty()
        );
    }

    private InternalServiceClientProperties invalidPrivateKeyJwtProperties() {
        return new InternalServiceClientProperties(
                true,
                "JWT_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "RS256",
                        "fraud-platform-local",
                        "ml-inference-service",
                        "",
                        "scoring-key-1",
                        "not-a-private-key",
                        "",
                        Duration.ofMinutes(5),
                        "ml-score"
                ),
                InternalServiceClientProperties.Mtls.empty()
        );
    }

    private InternalServiceClientProperties hs256JwtProperties() {
        return new InternalServiceClientProperties(
                true,
                "JWT_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                new InternalServiceClientProperties.Jwt(
                        "HS256",
                        "fraud-platform-local",
                        "ml-inference-service",
                        "local-dev-jwt-service-secret-32bytes",
                        "",
                        "",
                        "",
                        Duration.ofMinutes(5),
                        "ml-score"
                ),
                InternalServiceClientProperties.Mtls.empty()
        );
    }

    private InternalServiceClientProperties mtlsProperties() {
        return mtlsPropertiesWithCertificate("client.pem");
    }

    private InternalServiceClientProperties mtlsPropertiesWithCertificate(String certificatePath) {
        return mtlsPropertiesWithCertificateAndCa(certificatePath, "deployment/service-identity/mtls/local-dev-ca.pem");
    }

    private InternalServiceClientProperties mtlsPropertiesWithCertificateAndCa(String certificatePath, String caCertificatePaths) {
        return new InternalServiceClientProperties(
                true,
                "MTLS_SERVICE_IDENTITY",
                "fraud-scoring-service",
                "",
                false,
                InternalServiceClientProperties.Jwt.empty(),
                new InternalServiceClientProperties.Mtls(
                        repoPath(certificatePath).toString(),
                        "client-key.pem",
                        caPaths(caCertificatePaths),
                        "ml-inference-service",
                        false
                )
        );
    }

    private String caPaths(String caCertificatePaths) {
        return java.util.Arrays.stream(caCertificatePaths.split("[,;]"))
                .map(String::trim)
                .filter(path -> !path.isBlank())
                .map(path -> repoPath(path).toString())
                .reduce((left, right) -> left + "," + right)
                .orElse(caCertificatePaths);
    }

    private String privateKeyPem() {
        try {
            return Files.readString(repoPath("deployment/service-identity/fraud-scoring-service-private.pem"));
        } catch (IOException exception) {
            throw new IllegalStateException("Test private key is unavailable.");
        }
    }

    private Path repoPath(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        return Path.of(relativePath);
    }

    private InternalServiceClientProperties disabledProperties() {
        return new InternalServiceClientProperties(
                false,
                "DISABLED_LOCAL_ONLY",
                "fraud-scoring-service",
                "",
                false,
                InternalServiceClientProperties.Jwt.empty(),
                InternalServiceClientProperties.Mtls.empty()
        );
    }

}
