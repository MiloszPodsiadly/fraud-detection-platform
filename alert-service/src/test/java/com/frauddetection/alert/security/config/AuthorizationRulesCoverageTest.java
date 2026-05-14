package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AuthorizationRulesCoverageTest extends AbstractSecurityRouteBoundaryWebMvcTest {

    private static final Map<String, String> BROAD_ROUTE_MATCHER_ALLOWLIST = Map.of(
            "PublicTechnicalAuthorizationRules:\"/actuator/health/**\"",
            "Health subpaths are public for platform health probes only.",
            "PublicTechnicalAuthorizationRules:\"/assets/**\"",
            "Static SPA asset subtree is public read-only content.",
            "PublicTechnicalAuthorizationRules:\"/static/**\"",
            "Static SPA fallback asset subtree is public read-only content.",
            "SessionAuthorizationRules:\"/oauth2/**\"",
            "OAuth framework bootstrap routes are public GET-only and not business APIs.",
            "SessionAuthorizationRules:\"/login/oauth2/**\"",
            "OAuth framework callback routes are public GET-only and not business APIs."
    );

    @Test
    void fraudCaseRoutesRequireExplicitFraudCaseAuthority() throws Exception {
        expectSecurityLayerDoesNotReject(get("/api/v1/fraud-cases/work-queue")
                .with(userWith(AnalystAuthority.FRAUD_CASE_READ)));
        expectDenied(get("/api/v1/fraud-cases/work-queue")
                .with(userWith(AnalystAuthority.ALERT_READ)));
        expectDenied(get("/api/v1/fraud-cases/work-queue"));
        expectDenied(get("/api/v1/fraud-cases/not-real/sibling")
                .with(userWith(AnalystAuthority.FRAUD_CASE_READ)));
        expectDenied(post("/api/v1/fraud-cases/work-queue")
                .with(userWith(AnalystAuthority.FRAUD_CASE_READ)).with(csrf()));
    }

    @Test
    void alertRoutesRequireExplicitAlertAuthority() throws Exception {
        expectSecurityLayerDoesNotReject(get("/api/v1/alerts").with(userWith(AnalystAuthority.ALERT_READ)));
        expectDenied(get("/api/v1/alerts").with(userWith(AnalystAuthority.FRAUD_CASE_READ)));
        expectDenied(get("/api/v1/alerts"));
        expectDenied(get("/api/v1/alerts/not-real/sibling").with(userWith(AnalystAuthority.ALERT_READ)));
        expectDenied(post("/api/v1/alerts").with(userWith(AnalystAuthority.ALERT_READ)).with(csrf()));
    }

    @Test
    void transactionRoutesRequireExplicitTransactionAuthority() throws Exception {
        expectSecurityLayerDoesNotReject(get("/api/v1/transactions/scored")
                .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)));
        expectDenied(get("/api/v1/transactions/scored").with(userWith(AnalystAuthority.FRAUD_CASE_READ)));
        expectDenied(get("/api/v1/transactions/scored"));
        expectDenied(get("/api/v1/transactions/not-real")
                .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)));
        expectDenied(post("/api/v1/transactions/scored")
                .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)).with(csrf()));
    }

    @Test
    void governanceRoutesRequireExplicitGovernanceAuthorities() throws Exception {
        expectSecurityLayerDoesNotReject(get("/governance/advisories")
                .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)));
        expectDenied(get("/governance/advisories").with(userWith(AnalystAuthority.FRAUD_CASE_READ)));
        expectDenied(get("/governance/advisories"));
        expectDenied(get("/governance/not-real")
                .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)));
        expectDenied(post("/governance/advisories")
                .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)).with(csrf()));
    }

    @Test
    void auditRoutesRequireExplicitAuditAuthorities() throws Exception {
        expectSecurityLayerDoesNotReject(get("/api/v1/audit/integrity/external")
                .with(userWith(AnalystAuthority.AUDIT_VERIFY)));
        expectDenied(get("/api/v1/audit/integrity/external").with(userWith(AnalystAuthority.AUDIT_READ)));
        expectDenied(get("/api/v1/audit/integrity/external"));
        expectDenied(get("/api/v1/audit/not-real").with(userWith(AnalystAuthority.AUDIT_VERIFY)));
        expectDenied(post("/api/v1/audit/integrity/external")
                .with(userWith(AnalystAuthority.AUDIT_VERIFY)).with(csrf()));
    }

    @Test
    void trustRoutesRequireExplicitTrustAuthorities() throws Exception {
        expectSecurityLayerDoesNotReject(get("/system/trust-level")
                .with(userWith(AnalystAuthority.AUDIT_VERIFY)));
        expectDenied(get("/system/trust-level").with(userWith(AnalystAuthority.FRAUD_CASE_READ)));
        expectDenied(get("/system/trust-level"));
        expectDenied(get("/system/not-real").with(userWith(AnalystAuthority.AUDIT_VERIFY)));
        expectDenied(post("/system/trust-level").with(userWith(AnalystAuthority.AUDIT_VERIFY)).with(csrf()));
    }

    @Test
    void bffRoutesKeepLogoutExplicitAndDenyUnknownSiblings() throws Exception {
        expectSecurityLayerDoesNotReject(post("/bff/logout").with(reader()).with(csrf()));
        expectDenied(post("/bff/logout"));
        expectDenied(get("/bff/not-real").with(reader()));
        expectDenied(post("/bff/not-real").with(reader()).with(csrf()));
    }

    @Test
    void spaFallbackIsGetOnlyAndDoesNotCatchBackendLookingRoutes() throws Exception {
        expectSecurityLayerDoesNotReject(get("/analyst-console"));
        expectDenied(post("/analyst-console").with(reader()).with(csrf()));
        expectDenied(get("/api/anything").with(reader()));
        expectDenied(get("/api/v1/anything").with(reader()));
        expectDenied(get("/governance/anything").with(reader()));
        expectDenied(get("/system/anything").with(reader()));
        expectDenied(get("/bff/anything").with(reader()));
    }

    @Test
    void routeGroupClassesDoNotUseFinalDenyOrBroadPermitAll() {
        assertThat(SecurityRuleSource.routeGroupSourcesExceptDenyByDefault())
                .allSatisfy((name, source) -> {
                    assertThat(source).doesNotContain("anyRequest()");
                    assertThat(source).doesNotContain("\"/api/**\").permitAll()");
                    assertThat(source).doesNotContain("\"/api/v1/**\").permitAll()");
                    assertThat(source).doesNotContain("\"/governance/**\").permitAll()");
                    assertThat(source).doesNotContain("\"/system/**\").permitAll()");
                    assertThat(source).doesNotContain("\"/bff/**\").permitAll()");
                });
    }

    @Test
    void broadRouteMatchersRequireExplicitFdp49ReviewAllowlist() {
        assertThat(SecurityRuleSource.discoveredAuthorizationRuleGroups()
                .stream()
                .filter(group -> !"DenyByDefaultAuthorizationRules".equals(group))
                .filter(group -> !"SpaFallbackAuthorizationRules".equals(group))
                .flatMap(group -> broadRouteMatchers(group).stream()
                        .map(matcher -> new BroadRouteMatcher(group, matcher)))
                .filter(matcher -> !BROAD_ROUTE_MATCHER_ALLOWLIST.containsKey(matcher.group() + ":" + matcher.matcher()))
                .map(matcher -> "Broad route matcher " + matcher.matcher() + " in " + matcher.group()
                        + " requires explicit FDP-49 review and allowlist justification.")
                .toList())
                .isEmpty();
    }

    private java.util.List<String> broadRouteMatchers(String group) {
        String source = SecurityRuleSource.source(
                "src/main/java/com/frauddetection/alert/security/config/" + group + ".java");
        var matchers = new java.util.ArrayList<String>();
        var matcher = Pattern.compile("\"[^\"]+/\\*\\*\"").matcher(source);
        while (matcher.find()) {
            matchers.add(matcher.group());
        }
        return matchers;
    }

    private record BroadRouteMatcher(String group, String matcher) {
    }
}
