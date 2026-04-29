package com.frauddetection.trustauthority;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrustAuthorityController.class)
class TrustAuthorityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrustAuthorityService service;

    @Test
    void shouldReturn503WhenSignAuditSinkFails() throws Exception {
        when(service.sign(any(TrustAuthorityRequestCredentials.class), any(TrustSignRequest.class)))
                .thenThrow(new TrustAuthorityAuditException("failed", null));

        mockMvc.perform(post("/api/v1/trust/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signRequest()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldMapMissingInvalidAndReplayCredentialsToExplicitStatuses() throws Exception {
        when(service.sign(any(TrustAuthorityRequestCredentials.class), any(TrustSignRequest.class)))
                .thenThrow(new TrustAuthorityRequestException(HttpStatus.UNAUTHORIZED, "missing"))
                .thenThrow(new TrustAuthorityRequestException(HttpStatus.FORBIDDEN, "invalid"))
                .thenThrow(new TrustAuthorityRequestException(HttpStatus.CONFLICT, "replay"));

        mockMvc.perform(post("/api/v1/trust/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signRequest()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/trust/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signRequest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/trust/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signRequest()))
                .andExpect(status().isConflict());
    }

    private String signRequest() {
        return """
                {
                  "purpose": "AUDIT_ANCHOR",
                  "payload_hash": "hash-1",
                  "partition_key": "source_service:alert-service",
                  "chain_position": 1,
                  "anchor_id": "anchor-1"
                }
                """;
    }
}
