package com.frauddetection.alert.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.security.auth.OidcAnalystAuthoritiesMapper;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.security.session.AnalystSessionController;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.FraudCaseQueryService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({FraudCaseController.class, AnalystSessionController.class})
@Import({
        AlertSecurityConfig.class,
        CurrentAnalystUser.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        FraudCaseResponseMapper.class,
        AlertResponseMapper.class,
        AlertServiceExceptionHandler.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.bff.enabled=true",
        "app.security.bff.provider-logout-uri=http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout",
        "app.security.bff.post-logout-redirect-uri=http://localhost:4173/",
        "app.security.bff.client-id=analyst-console-ui"
})
class BffSessionSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockBean
    private FraudCaseQueryService fraudCaseQueryService;

    @MockBean
    private AlertServiceMetrics alertServiceMetrics;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @MockBean
    private OidcAnalystAuthoritiesMapper oidcAnalystAuthoritiesMapper;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void sessionEndpointReturnsAnonymousNoStoreResponseWithoutTokenFields() throws Exception {
        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.sessionStatus").value("ANONYMOUS"))
                .andExpect(jsonPath("$.userId").value(""))
                .andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.authorities").isEmpty())
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andExpect(jsonPath("$.id_token").doesNotExist())
                .andExpect(jsonPath("$.jwt").doesNotExist())
                .andExpect(jsonPath("$.claims").doesNotExist())
                .andExpect(content().string(not(containsString("accessToken"))))
                .andExpect(content().string(not(containsString("refreshToken"))))
                .andExpect(content().string(not(containsString("idToken"))))
                .andExpect(content().string(not(containsString("claims"))))
                .andExpect(content().string(not(containsString("groups"))))
                .andExpect(content().string(not(containsString("email"))))
                .andExpect(content().string(not(containsString("profile"))))
                .andExpect(content().string(not(containsString("sessionId"))));

        verify(alertServiceMetrics).recordAnalystSessionRequest("anonymous", true);
    }

    @Test
    void sessionEndpointReturnsOidcIdentityCsrfAndNoTokenFields() throws Exception {
        mockMvc.perform(get("/api/v1/session").with(oidcLogin()
                        .idToken(token -> token.subject("oidc-user-1"))
                        .authorities(
                                new SimpleGrantedAuthority("ROLE_FRAUD_OPS_ADMIN"),
                                new SimpleGrantedAuthority(AnalystAuthority.FRAUD_CASE_READ)
                        )))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.sessionStatus").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.userId").value("oidc-user-1"))
                .andExpect(jsonPath("$.roles[0]").value("FRAUD_OPS_ADMIN"))
                .andExpect(jsonPath("$.authorities[0]").value(AnalystAuthority.FRAUD_CASE_READ))
                .andExpect(jsonPath("$.csrf.headerName").isNotEmpty())
                .andExpect(jsonPath("$.csrf.token").isNotEmpty())
                .andExpect(content().string(not(containsString("access_token"))))
                .andExpect(content().string(not(containsString("refresh_token"))))
                .andExpect(content().string(not(containsString("id_token"))))
                .andExpect(content().string(not(containsString("jwt"))))
                .andExpect(content().string(not(containsString("claims"))))
                .andExpect(content().string(not(containsString("accessToken"))))
                .andExpect(content().string(not(containsString("refreshToken"))))
                .andExpect(content().string(not(containsString("idToken"))))
                .andExpect(content().string(not(containsString("groups"))))
                .andExpect(content().string(not(containsString("email"))))
                .andExpect(content().string(not(containsString("profile"))))
                .andExpect(content().string(not(containsString("sessionId"))));

        verify(alertServiceMetrics).recordAnalystSessionRequest("authenticated", true);
    }

    @Test
    void sessionEndpointFailsClosedForAuthenticatedPrincipalWithoutUserId() throws Exception {
        mockMvc.perform(get("/api/v1/session").with(authentication(new UsernamePasswordAuthenticationToken(
                        new AnalystPrincipal("", Set.of(AnalystRole.FRAUD_OPS_ADMIN), Set.of(AnalystAuthority.FRAUD_CASE_READ)),
                        null,
                        List.of(new SimpleGrantedAuthority(AnalystAuthority.FRAUD_CASE_READ))
                ))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("\"authenticated\":true"))));

        verify(alertServiceMetrics).recordAnalystSessionRequest("auth_error", false);
        verify(alertServiceMetrics).recordAnalystSessionInvalidPrincipal("other");
    }

    @Test
    void cookieBackedMutationWithoutCsrfIsRejectedBeforeBusinessLayer() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isForbidden());

        verify(fraudCaseManagementService, never()).updateCase(any(), any(), any());
        verify(alertServiceMetrics).recordBffCsrfRejection(any());
    }

    @Test
    void cookieBackedMutationWithCsrfStillRequiresAuthority() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ))
                        .with(csrf())
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isForbidden());

        verify(fraudCaseManagementService, never()).updateCase(any(), any(), any());
    }

    @Test
    void cookieBackedMutationWithCsrfAndAuthorityReachesBusinessLayer() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any(), eq("case-update-1")))
                .thenReturn(updateFraudCaseResponse());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .with(csrf())
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("case-1"));
    }

    @Test
    void bearerJunkWithSessionCookieCannotBypassCsrf() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .cookie(new Cookie("JSESSIONID", "session-1"))
                        .header("Authorization", "Bearer junk")
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isForbidden());

        verify(fraudCaseManagementService, never()).updateCase(any(), any(), any());
    }

    @Test
    void statelessBearerMutationWithoutSessionCookieIsNotRejectedByCsrf() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any(), eq("case-update-1")))
                .thenReturn(updateFraudCaseResponse());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(AnalystAuthority.FRAUD_CASE_UPDATE)))
                        .header("Authorization", "Bearer token-admin")
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("case-1"));
    }

    @Test
    void malformedBearerWithoutValidAuthenticationCannotReachBusinessLayer() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("Authorization", "Bearer malformed")
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isUnauthorized());

        verify(fraudCaseManagementService, never()).updateCase(any(), any(), any());
    }

    @Test
    void logoutWithCsrfReturnsProviderRedirectAndDeletesCookies() throws Exception {
        mockMvc.perform(post("/bff/logout")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0))
                .andExpect(jsonPath("$.logoutUrl").value(containsString("/protocol/openid-connect/logout")))
                .andExpect(content().string(not(containsString("Bearer"))))
                .andExpect(content().string(not(containsString("id_token"))));

        verify(alertServiceMetrics).recordBffLogoutRequest("success", "provider");
    }

    @Test
    void logoutMissingCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/bff/logout").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());

        verify(alertServiceMetrics).recordBffCsrfRejection(any());
        verify(alertServiceMetrics).recordBffLogoutRequest("rejected", "none");
    }

    @Test
    void unknownApiRouteRemainsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/not-a-real-endpoint")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicSpaStaticAndSessionRoutesAreAllowedBySecurity() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(get("/analyst-console"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(get("/assets/app.js"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isOk());
    }

    @Test
    void spaFallbackDoesNotAllowUnsafeMethodsOrUnknownBackendRoutes() throws Exception {
        mockMvc.perform(post("/analyst-console").with(userWith(AnalystAuthority.FRAUD_CASE_READ)).with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/not-a-real-endpoint").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/governance/not-a-real-endpoint").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/system/not-a-real-endpoint").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/bff/not-a-real-endpoint").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/random-backend-looking-route").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedGovernanceAndSystemRoutesRemainAuthorityGated() throws Exception {
        mockMvc.perform(get("/system/trust-level").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/governance/advisories").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/governance/advisories/event-1/audit")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue")
                        .with(userWith(AnalystAuthority.ALERT_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void narrowSpaFallbackAllowsOnlyKnownGetRoutes() throws Exception {
        mockMvc.perform(get("/fraud-case"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(get("/reports"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(post("/reports").with(userWith(AnalystAuthority.FRAUD_CASE_READ)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor userWith(String... authorities) {
        return authentication(new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()
        ));
    }

    private UpdateFraudCaseRequest updateFraudCaseRequest() {
        return new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-1",
                "Confirmed rapid transfer abuse.",
                List.of("rapid-transfer")
        );
    }

    private UpdateFraudCaseResponse updateFraudCaseResponse() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setStatus(FraudCaseStatus.CONFIRMED_FRAUD);
        FraudCaseResponse updated = new FraudCaseResponse(
                document.getCaseId(),
                document.getCustomerId(),
                null,
                document.getStatus(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of()
        );
        return new UpdateFraudCaseResponse(
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING,
                null,
                "idem-hash",
                "case-1",
                null,
                updated,
                null
        );
    }
}
