package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class SecurityMatcherOrderRegressionTest extends AbstractSecurityRouteBoundaryWebMvcTest {

    @Test
    void publicRoutesRemainPublic() throws Exception {
        expectSecurityAllowsThrough(get("/api/v1/session"));
        expectSecurityAllowsThrough(get("/oauth2/authorization/oidc"));
        expectSecurityAllowsThrough(get("/login/oauth2/code/oidc"));
        expectSecurityAllowsThrough(get("/actuator/health"));
    }

    @Test
    void protectedBusinessRoutesRemainProtectedWithoutAuthority() throws Exception {
        expectDenied(get("/api/v1/fraud-cases/work-queue"));
        expectDenied(get("/api/v1/transactions/scored"));
        expectDenied(get("/governance/advisories"));
        expectDenied(get("/system/trust-level"));
    }

    @Test
    void denyGuardrailsBeatSpaFallback() throws Exception {
        for (String path : new String[] {
                "/api/anything",
                "/api/v1/anything",
                "/governance/anything",
                "/system/anything",
                "/bff/anything",
                "/actuator/anything-unknown"
        }) {
            mockMvc.perform(get(path).with(reader()))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as(path)
                            .isIn(401, 403, 404))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as(path)
                            .isNotEqualTo(200));
        }
    }

    @Test
    void spaFallbackIsGetOnly() throws Exception {
        expectSecurityAllowsThrough(get("/analyst-console"));
        expectDenied(post("/analyst-console").with(reader()).with(csrf()));
        expectDenied(put("/analyst-console").with(reader()).with(csrf()));
        expectDenied(delete("/analyst-console").with(reader()).with(csrf()));
        expectDenied(patch("/analyst-console").with(reader()).with(csrf()));
    }

    @Test
    void finalDenyCannotBeOverriddenByRouteGroups() throws Exception {
        for (String path : new String[] {
                "/api/v1/unknown",
                "/api/unknown",
                "/governance/unknown",
                "/system/unknown",
                "/bff/unknown",
                "/random-backend-looking-route"
        }) {
            mockMvc.perform(get(path).with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as(path)
                            .isNotEqualTo(200));
        }
    }

    @Test
    void compositionOrderPreservesFailClosedMatcherSequence() {
        String source = SecurityRuleSource.source(
                "src/main/java/com/frauddetection/alert/security/config/AlertEndpointAuthorizationRules.java");

        assertThat(source.indexOf("publicTechnicalAuthorizationRules.configure(authorize)"))
                .isLessThan(source.indexOf("alertAuthorizationRules.configure(authorize)"));
        assertThat(source.indexOf("governanceAuthorizationRules.configure(authorize)"))
                .isLessThan(source.indexOf("bffAuthorizationRules.configure(authorize)"));
        assertThat(source.indexOf("bffAuthorizationRules.configure(authorize)"))
                .isLessThan(source.indexOf("configureBackendRouteFamilies(authorize)"));
        assertThat(source.indexOf("configureBackendRouteFamilies(authorize)"))
                .isLessThan(source.indexOf("spaFallbackAuthorizationRules.configure(authorize)"));
        assertThat(source.indexOf("spaFallbackAuthorizationRules.configure(authorize)"))
                .isLessThan(source.indexOf("configureFinalDeny(authorize)"));
    }
}
