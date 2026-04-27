package com.frauddetection.alert.audit;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuditTrustAttestationController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class AuditTrustAttestationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditTrustAttestationService attestationService;

    @Test
    void shouldReturnStableTrustAttestationContract() throws Exception {
        when(attestationService.attest(eq("alert-service"), eq(100), isNull()))
                .thenReturn(new AuditTrustAttestationResponse(
                        "AVAILABLE",
                        AuditTrustLevel.INTERNAL_ONLY,
                        "VALID",
                        "PARTIAL",
                        "DISABLED",
                        AuditTrustAttestationResponse.AnchorCoverage.empty(),
                        7L,
                        "hash-1",
                        null,
                        "fingerprint",
                        null,
                        null,
                        "disabled",
                        "NONE",
                        "OPTIONAL",
                        "alert-service",
                        100,
                        List.of("not_legal_notarization")
                ));

        mockMvc.perform(get("/api/v1/audit/trust/attestation")
                        .param("source_service", "alert-service")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.trust_level").value("INTERNAL_ONLY"))
                .andExpect(jsonPath("$.internal_integrity_status").value("VALID"))
                .andExpect(jsonPath("$.external_integrity_status").value("PARTIAL"))
                .andExpect(jsonPath("$.external_anchor_status").value("DISABLED"))
                .andExpect(jsonPath("$.anchor_coverage.total_anchors_checked").value(0))
                .andExpect(jsonPath("$.latest_chain_position").value(7))
                .andExpect(jsonPath("$.latest_event_hash").value("hash-1"))
                .andExpect(jsonPath("$.attestation_fingerprint").value("fingerprint"))
                .andExpect(jsonPath("$.attestation_signature").doesNotExist())
                .andExpect(jsonPath("$.signer_mode").value("disabled"))
                .andExpect(jsonPath("$.attestation_signature_strength").value("NONE"))
                .andExpect(jsonPath("$.external_trust_dependency").value("OPTIONAL"))
                .andExpect(jsonPath("$.source_service").value("alert-service"))
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.limitations[0]").value("not_legal_notarization"));
    }
}
