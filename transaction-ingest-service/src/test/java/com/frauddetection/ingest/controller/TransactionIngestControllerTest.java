package com.frauddetection.ingest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.exception.TransactionIngestExceptionHandler;
import com.frauddetection.ingest.service.TransactionIngestUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static com.frauddetection.ingest.observability.CorrelationIdContext.CORRELATION_ID_HEADER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionIngestController.class)
@Import(TransactionIngestExceptionHandler.class)
class TransactionIngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionIngestUseCase transactionIngestUseCase;

    @Test
    void shouldAcceptValidTransactionRequest() throws Exception {
        given(transactionIngestUseCase.ingest(any(IngestTransactionRequest.class)))
                .willReturn(new IngestTransactionResponse(
                        "txn-1001",
                        "event-1001",
                        "corr-1001",
                        "transactions.raw",
                        Instant.parse("2026-04-20T10:15:30Z"),
                        "ACCEPTED"
                ));

        mockMvc.perform(post("/api/v1/transactions")
                        .header(CORRELATION_ID_HEADER, "corr-request-1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TransactionIngestRequestTestData.validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(header().string(CORRELATION_ID_HEADER, "corr-request-1001"))
                .andExpect(jsonPath("$.transactionId").value("txn-1001"))
                .andExpect(jsonPath("$.topic").value("transactions.raw"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void shouldRejectInvalidTransactionRequest() throws Exception {
        IngestTransactionRequest invalidRequest = new IngestTransactionRequest(
                "",
                "cust-1001",
                "acct-1001",
                "card-1001",
                Instant.parse("2026-04-20T10:15:28Z"),
                TransactionIngestRequestTestData.validRequest().transactionAmount(),
                TransactionIngestRequestTestData.validRequest().merchantInfo(),
                TransactionIngestRequestTestData.validRequest().deviceInfo(),
                TransactionIngestRequestTestData.validRequest().locationInfo(),
                TransactionIngestRequestTestData.validRequest().customerContext(),
                "PURCHASE",
                "3DS",
                "GATEWAY",
                "trace-1001",
                null
        );

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.details[0]").exists());
    }

    @Test
    void shouldRejectMalformedJsonWithoutLeakingInternalDetails() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request."))
                .andExpect(jsonPath("$.details").isEmpty());
    }
}
