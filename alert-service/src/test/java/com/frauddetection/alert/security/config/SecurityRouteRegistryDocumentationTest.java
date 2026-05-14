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
                .contains("Every public endpoint must explain why it is public")
                .contains("## Maintainer checklist for new endpoints")
                .contains("Add the route owner to `SecurityRouteOwnershipRegistry`")
                .contains("Keep frontend auth-sensitive calls behind `createAlertsApiClient({ session, authProvider })`");
    }

    @Test
    void routeOwnershipMapMentionsEveryAuthorizationRuleGroup() throws IOException {
        String doc = readEndpointAuthorizationMap();

        assertThat(doc).contains("FDP-49 is security architecture hardening only");
        assertThat(SecurityRuleSource.ROUTE_GROUPS)
                .allSatisfy(group -> assertThat(doc).contains(group));
        assertThat(SecurityRouteOwnershipRegistry.DENY_BY_DEFAULT_FAMILIES)
                .allSatisfy(routeFamily -> assertThat(doc).contains(routeFamily));
        assertThat(SecurityRouteOwnershipRegistry.MVC_ROUTES)
                .allSatisfy(route -> assertThat(doc)
                        .as(route.method() + " " + route.pattern())
                        .contains(route.owner()));
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
                .contains("not a secret against XSS")
                .contains("must not expose an access token, refresh token, ID token")
                .contains("Frontend roles/authorities are display/capability hints only")
                .contains("Production BFF hardening remains deployment responsibility unless configured in this repo")
                .contains("Backend Spring Security remains the enforcement source");
    }

    @Test
    void docsNameNonGoalsAndMetricsOwnershipFollowUp() throws IOException {
        String doc = readEndpointAuthorizationMap();

        assertThat(doc)
                .contains("## Non-Goals")
                .contains("does not add business endpoints")
                .contains("change `RegulatedMutationCoordinator`")
                .contains("change Kafka/outbox/finality behavior")
                .contains("add UI product features")
                .contains("## Metrics ownership follow-up")
                .contains("AuthSecurityMetrics")
                .contains("FraudCaseMetrics")
                .contains("TransactionMetrics")
                .contains("avoid high-cardinality labels");
    }

    private String readEndpointAuthorizationMap() throws IOException {
        Path doc = SecurityRuleSource.repositoryFile("docs/security/endpoint-authorization-map.md");
        assertThat(doc).exists();
        return Files.readString(doc);
    }
}
