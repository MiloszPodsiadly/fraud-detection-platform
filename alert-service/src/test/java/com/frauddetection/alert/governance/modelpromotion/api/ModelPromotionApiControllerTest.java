package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessResultCountExtractor;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceEnvironment;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionIntendedUse;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPersistenceConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.IdentityResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ScopeResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.StateResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ModelPromotionApiController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({AlertServiceExceptionHandler.class, ReadAccessAuditClassifier.class, ReadAccessResultCountExtractor.class})
class ModelPromotionApiControllerTest {

    private static final String SHA256 = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelPromotionApiService service;

    @MockitoBean
    private ModelPromotionApiRateLimiter rateLimiter;

    @MockitoBean
    private ReadAccessAuditService readAccessAuditService;

    @BeforeEach
    void allowRequests() {
        doNothing().when(rateLimiter).enforce(any());
    }

    @Test
    void stateUsesFixedInstitutionalScopeAndReturnsBoundedHistory() throws Exception {
        when(service.state(any(), eq(25))).thenReturn(stateResponse());

        mockMvc.perform(get("/api/v1/governance/model-promotion/state")
                        .queryParam("modelName", "fraud-model")
                        .queryParam("modelVersion", "v1")
                        .queryParam("featureContractVersion", "features-v1")
                        .queryParam("modelArtifactSha256", SHA256)
                        .queryParam("environment", "TEST")
                        .queryParam("intendedUse", "FRAUD_RISK_SCORING")
                        .queryParam("historyLimit", "25")
                        .principal(oidcAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.governanceScope.institutionalScope").value("CURRENT_INSTITUTION"))
                .andExpect(jsonPath("$.currentRevision").value(0))
                .andExpect(jsonPath("$.deploymentStatus")
                        .value("NOT_AUTHORIZED_BY_MODEL_PROMOTION_GOVERNANCE"));

        ArgumentCaptor<ModelPromotionApiContracts.Selection> selection = ArgumentCaptor.forClass(
                ModelPromotionApiContracts.Selection.class
        );
        verify(service).state(selection.capture(), eq(25));
        assertThat(selection.getValue().governanceScope().institutionalScope())
                .isEqualTo("CURRENT_INSTITUTION");
        ArgumentCaptor<String> rateLimitIdentity = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).enforce(rateLimitIdentity.capture());
        assertThat(rateLimitIdentity.getValue()).matches("oidc:[0-9a-f]{64}")
                .doesNotContain("governance-operator", "issuer.example");
    }

    @Test
    void rejectsMalformedProposalBeforeCallingWorkflow() throws Exception {
        mockMvc.perform(post("/api/v1/governance/model-promotion/proposals")
                        .contentType("application/json")
                        .content("""
                                {
                                  "selection": {
                                    "modelName": "fraud-model",
                                    "modelVersion": "v1",
                                    "featureContractVersion": "features-v1",
                                    "modelArtifactSha256": "%s",
                                    "environment": "TEST",
                                    "intendedUse": "FRAUD_RISK_SCORING"
                                  },
                                  "sourceState": "CANDIDATE",
                                  "expectedRevision": 0,
                                  "requestedTargetState": "CHALLENGER",
                                  "fromInclusive": "2026-01-01T00:00:00Z",
                                  "toInclusive": "2026-01-02T00:00:00Z",
                                  "operationId": "proposal-op-1",
                                  "proposalId": "proposal-1",
                                  "rationale": "The evidence requires independent review.",
                                  "rationaleSha256": "not-a-sha"
                                }
                                """.formatted(SHA256))
                        .principal(oidcAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed."));
    }

    @Test
    void returnsServiceUnavailableWhenMandatoryGovernanceDependencyIsUnavailable() throws Exception {
        when(service.state(any(), eq(20))).thenThrow(new ModelPromotionApiUnavailableException("unavailable", null));

        mockMvc.perform(get("/api/v1/governance/model-promotion/state")
                        .queryParam("modelName", "fraud-model")
                        .queryParam("modelVersion", "v1")
                        .queryParam("featureContractVersion", "features-v1")
                        .queryParam("modelArtifactSha256", SHA256)
                        .queryParam("environment", "TEST")
                        .queryParam("intendedUse", "FRAUD_RISK_SCORING")
                        .principal(oidcAuthentication()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details[0]").value("reason:MODEL_PROMOTION_DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void returnsConflictForAStaleLifecycleCommand() throws Exception {
        when(service.propose(any())).thenThrow(new ModelPromotionPersistenceConflictException(
                "Transition revision does not match the authoritative aggregate"
        ));

        mockMvc.perform(post("/api/v1/governance/model-promotion/proposals")
                        .contentType("application/json")
                        .content("""
                                {
                                  "selection": {
                                    "modelName": "fraud-model",
                                    "modelVersion": "v1",
                                    "featureContractVersion": "features-v1",
                                    "modelArtifactSha256": "%s",
                                    "environment": "TEST",
                                    "intendedUse": "FRAUD_RISK_SCORING"
                                  },
                                  "sourceState": "CANDIDATE",
                                  "expectedRevision": 0,
                                  "requestedTargetState": "CHALLENGER",
                                  "fromInclusive": "2026-01-01T00:00:00Z",
                                  "toInclusive": "2026-01-02T00:00:00Z",
                                  "operationId": "proposal-op-1",
                                  "proposalId": "proposal-1",
                                  "rationale": "The evidence requires independent review.",
                                  "rationaleSha256": "%s"
                                }
                                """.formatted(SHA256, SHA256))
                        .principal(oidcAuthentication()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Transition revision does not match the authoritative aggregate"
                ));
    }

    @Test
    void rejectsRequestsThatExceedTheModelPromotionRateLimit() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Model promotion API rate limit exceeded.")).when(rateLimiter).enforce(any());

        mockMvc.perform(get("/api/v1/governance/model-promotion/state")
                        .queryParam("modelName", "fraud-model")
                        .queryParam("modelVersion", "v1")
                        .queryParam("featureContractVersion", "features-v1")
                        .queryParam("modelArtifactSha256", SHA256)
                        .queryParam("environment", "TEST")
                        .queryParam("intendedUse", "FRAUD_RISK_SCORING")
                        .principal(oidcAuthentication()))
                .andExpect(status().isTooManyRequests());
        verify(service, never()).state(any(), eq(20));
    }

    @Test
    void returnsServiceUnavailableWhenTheSharedRateLimiterIsUnavailable() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Model promotion API rate limiter is unavailable.")).when(rateLimiter).enforce(any());

        mockMvc.perform(get("/api/v1/governance/model-promotion/state")
                        .queryParam("modelName", "fraud-model")
                        .queryParam("modelVersion", "v1")
                        .queryParam("featureContractVersion", "features-v1")
                        .queryParam("modelArtifactSha256", SHA256)
                        .queryParam("environment", "TEST")
                        .queryParam("intendedUse", "FRAUD_RISK_SCORING")
                        .principal(oidcAuthentication()))
                .andExpect(status().isServiceUnavailable());
    }

    private UsernamePasswordAuthenticationToken oidcAuthentication() {
        Instant now = Instant.now();
        OidcIdToken token = new OidcIdToken("test-token", now, now.plusSeconds(300), Map.of(
                "iss", "https://issuer.example.test", "sub", "governance-operator"));
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_TEST")), token);
        return new UsernamePasswordAuthenticationToken(user, "test", user.getAuthorities());
    }

    private StateResponse stateResponse() {
        return new StateResponse(
                new IdentityResponse("fraud-model", "v1", "features-v1", SHA256),
                new ScopeResponse("CURRENT_INSTITUTION", ModelGovernanceEnvironment.TEST),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                com.frauddetection.alert.governance.modelpromotion.ModelPromotionLifecycleState.CANDIDATE,
                0,
                null,
                List.of(com.frauddetection.alert.governance.modelpromotion.ModelPromotionLifecycleState.CHALLENGER),
                false,
                "NOT_AUTHORIZED_BY_MODEL_PROMOTION_GOVERNANCE",
                List.of(),
                List.of()
        );
    }
}
