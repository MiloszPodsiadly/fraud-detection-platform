package com.frauddetection.alert.controller;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ScoredTransactionController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({AlertResponseMapper.class, ScoredTransactionResponseMapper.class, AlertServiceExceptionHandler.class})
class ScoredTransactionControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionMonitoringUseCase transactionMonitoringUseCase;

    @Test
    void shouldRejectDeepPageBeforeSearch() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/scored")
                        .queryParam("page", "1001")
                        .queryParam("size", "25"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transactionMonitoringUseCase);
    }

    @Test
    void shouldRejectTooLongQueryWithoutEchoingRawQuery() throws Exception {
        String rawQuery = "card-4111111111111111-customer-secret-".repeat(4);

        String body = mockMvc.perform(get("/api/v1/transactions/scored")
                        .queryParam("query", rawQuery)
                        .queryParam("size", "25"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(rawQuery, "4111111111111111", "customer-secret");
        verifyNoInteractions(transactionMonitoringUseCase);
    }
}
