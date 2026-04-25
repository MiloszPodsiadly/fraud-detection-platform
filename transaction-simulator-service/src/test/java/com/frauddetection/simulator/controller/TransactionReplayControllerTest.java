package com.frauddetection.simulator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.simulator.api.ReplaySourceType;
import com.frauddetection.simulator.api.ReplayStartRequest;
import com.frauddetection.simulator.api.ReplayStatusResponse;
import com.frauddetection.simulator.api.ReplayStopResponse;
import com.frauddetection.simulator.exception.ReplayExceptionHandler;
import com.frauddetection.simulator.service.TransactionReplayUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionReplayController.class)
@Import(ReplayExceptionHandler.class)
class TransactionReplayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionReplayUseCase transactionReplayUseCase;

    @Test
    void shouldStartReplay() throws Exception {
        given(transactionReplayUseCase.startReplay(any()))
                .willReturn(new ReplayStatusResponse(
                        "RUNNING",
                        ReplaySourceType.SYNTHETIC,
                        5,
                        0,
                        0,
                        0,
                        0L,
                        Instant.parse("2026-04-20T10:15:30Z"),
                        null,
                        "Replay started."
                ));

        mockMvc.perform(post("/api/v1/replay/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReplayStartRequest(ReplaySourceType.SYNTHETIC, 5, 0L))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.requestedEvents").value(5));
    }

    @Test
    void shouldRejectInvalidReplayRequest() throws Exception {
        mockMvc.perform(post("/api/v1/replay/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReplayStartRequest(ReplaySourceType.SYNTHETIC, 0, -1L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.details[0]").exists());
    }

    @Test
    void shouldRejectMalformedJsonWithoutLeakingInternalDetails() throws Exception {
        mockMvc.perform(post("/api/v1/replay/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request."))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void shouldReturnReplayStatus() throws Exception {
        given(transactionReplayUseCase.getReplayStatus())
                .willReturn(new ReplayStatusResponse(
                        "COMPLETED",
                        ReplaySourceType.SYNTHETIC,
                        5,
                        5,
                        5,
                        0,
                        0L,
                        Instant.parse("2026-04-20T10:15:30Z"),
                        Instant.parse("2026-04-20T10:15:32Z"),
                        "Replay completed."
                ));

        mockMvc.perform(get("/api/v1/replay/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.publishedEvents").value(5));
    }

    @Test
    void shouldStopReplay() throws Exception {
        given(transactionReplayUseCase.stopReplay())
                .willReturn(new ReplayStopResponse("STOPPING", "Replay stop requested."));

        mockMvc.perform(post("/api/v1/replay/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPING"));
    }
}
