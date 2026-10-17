package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceEnvironment;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionIntendedUse;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPersistenceConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.IdentityResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.ScopeResponse;
import static com.frauddetection.alert.governance.modelpromotion.api.ModelPromotionApiContracts.StateResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
@Import(AlertServiceExceptionHandler.class)
class ModelPromotionApiControllerTest {

    private static final String SHA256 = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelPromotionApiService service;

    @MockitoBean
    private ModelPromotionApiRateLimiter rateLimiter;

    @BeforeEach
    void allowRequests() {
        when(rateLimiter.allow(any())).thenReturn(true);
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
                        .queryParam("historyLimit", "25"))
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
                                  "rationaleSha256": "not-a-sha"
                                }
                                """.formatted(SHA256)))
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
                        .queryParam("intendedUse", "FRAUD_RISK_SCORING"))
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
                                  "rationaleSha256": "%s"
                                }
                                """.formatted(SHA256, SHA256)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Transition revision does not match the authoritative aggregate"
                ));
    }

    @Test
    void rejectsRequestsThatExceedTheModelPromotionRateLimit() throws Exception {
        when(rateLimiter.allow(any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/governance/model-promotion/state")
                        .queryParam("modelName", "fraud-model")
                        .queryParam("modelVersion", "v1")
                        .queryParam("featureContractVersion", "features-v1")
                        .queryParam("modelArtifactSha256", SHA256)
                        .queryParam("environment", "TEST")
                        .queryParam("intendedUse", "FRAUD_RISK_SCORING"))
                .andExpect(status().isTooManyRequests());
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
