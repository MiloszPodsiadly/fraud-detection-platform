package com.frauddetection.alert.security.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRouteRegistryDocumentationTest {

    @Test
    void docsDescribeEndpointRegistrationGuardrails() throws IOException {
        String doc = readEndpointAuthorizationMap();

        assertThat(doc)
                .contains("deny by default")
                .contains("explicitly owned by a route group")
                .contains("Never add broad `permitAll`")
                .contains("SPA fallback must remain GET-only")
                .contains("Unsafe cookie-backed routes require CSRF")
                .contains("Stateless bearer APIs may bypass CSRF only when no `JSESSIONID` is present")
                .contains("Every public endpoint must explain why it is public");
    }

    @Test
    void routeOwnershipMapMentionsEveryAuthorizationRuleGroup() throws IOException {
        String doc = readEndpointAuthorizationMap();

        assertThat(doc).contains("FDP-49 is security architecture hardening only");
        assertThat(SecurityRuleSource.ROUTE_GROUPS)
                .allSatisfy(group -> assertThat(doc).contains(group));
    }

    @Test
    void docsStateProductionBffBoundariesWithoutIamOverclaim() throws IOException {
        String doc = readEndpointAuthorizationMap();

        assertThat(doc)
                .contains("HTTPS-only ingress")
                .contains("Secure session cookie")
                .contains("SameSite policy selected and documented")
                .contains("direct SPA OIDC disabled unless separately approved")
                .contains("demo auth disabled")
                .contains("FDP-49 does not certify enterprise IAM readiness")
                .contains("CSRF token is not authentication material")
                .contains("Backend Spring Security remains the enforcement source");
    }

    private String readEndpointAuthorizationMap() throws IOException {
        Path doc = SecurityRuleSource.repositoryFile("docs/security/endpoint-authorization-map.md");
        assertThat(doc).exists();
        return Files.readString(doc);
    }
}
