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
        controllers = AuditIntegrityController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class AuditIntegrityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditIntegrityService auditIntegrityService;

    @Test
    void shouldReturnStableIntegrityContract() throws Exception {
        when(auditIntegrityService.verify(isNull(), isNull(), eq("alert-service"), eq(100)))
                .thenReturn(new AuditIntegrityResponse(
                        "VALID",
                        2,
                        100,
                        null,
                        null,
                        "hash-1",
                        "hash-2",
                        List.of()
                ));

        mockMvc.perform(get("/api/v1/audit/integrity")
                        .param("source_service", "alert-service")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.checked").value(2))
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.first_event_hash").value("hash-1"))
                .andExpect(jsonPath("$.last_event_hash").value("hash-2"))
                .andExpect(jsonPath("$.violations").isEmpty())
                .andExpect(jsonPath("$.reason_code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void shouldReturnBadRequestForInvalidIntegrityQuery() throws Exception {
        when(auditIntegrityService.verify(isNull(), isNull(), eq("unknown-service"), isNull()))
                .thenThrow(new InvalidAuditEventQueryException(List.of("source_service: unsupported value")));

        mockMvc.perform(get("/api/v1/audit/integrity").param("source_service", "unknown-service"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid audit event query."))
                .andExpect(jsonPath("$.details[0]").value("source_service: unsupported value"));
    }
}
