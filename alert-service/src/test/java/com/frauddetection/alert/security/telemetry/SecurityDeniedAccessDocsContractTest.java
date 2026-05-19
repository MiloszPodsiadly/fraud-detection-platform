package com.frauddetection.alert.security.telemetry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessDocsContractTest {

    @Test
    void docsDescribeSecurityLayerDeniedAccessTelemetryWithoutOverclaims() throws Exception {
        String docs = Files.readString(Path.of("../docs/security/security_denied_access_telemetry.md"));
        String normalized = docs.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("complements fdp-64")
                .contains("security-layer denied access telemetry")
                .contains("401 and 403 decisions can happen before controllers run")
                .contains("allowed labels")
                .contains("routegroup")
                .contains("outcome")
                .contains("method")
                .contains("authstate")
                .contains("raw paths")
                .contains("full urls")
                .contains("query strings")
                .contains("path variables")
                .contains("cursor tokens")
                .contains("authorization header")
                .contains("jwt or token values")
                .contains("username or email")
                .contains("exception message")
                .contains("does not change authentication behavior")
                .contains("does not change authorization behavior")
                .contains("add roles")
                .contains("add permissions")
                .contains("not audit assurance")
                .contains("not a security guarantee")
                .contains("not fraud evidence")
                .contains("not legal evidence")
                .contains("metric contract change")
                .contains("replaces the previous access-denied metric tag schema")
                .contains("route group maintenance")
                .contains("raw path values must never be emitted as a fallback")
                .contains("out of scope")
                .contains("404 not found responses")
                .contains("405 method not allowed responses")
                .contains("does not log every denied request")
                .contains("logs are limited to bounded telemetry failure diagnostics")
                .contains("`authstate` is intentionally coarse")
                .contains("must not include username, email, subject, session id, client id, token metadata, ip, user agent, or role list");
        assertThat(normalized).doesNotContain(
                "complete security audit",
                "guaranteed attack detection",
                "identifies malicious user",
                "fraud proof",
                "legal proof",
                "is a security guarantee"
        );
    }
}
