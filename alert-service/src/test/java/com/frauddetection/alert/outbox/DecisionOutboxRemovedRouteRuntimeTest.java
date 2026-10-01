package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class DecisionOutboxRemovedRouteRuntimeTest {

    private OutboxRecoveryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(OutboxRecoveryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new OutboxRecoveryController(service, mock(SensitiveReadAuditService.class))
        ).build();
    }

    @Test
    void alertOwnedDecisionOutboxRoutesAreNotRegistered() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/decision-outbox/unknown-confirmations")).andReturn();
        MvcResult resolve = mockMvc.perform(
                post("/api/v1/decision-outbox/unknown-confirmations/alert-1/resolve")
        ).andReturn();

        assertThat(list.getHandler()).isNull();
        assertThat(resolve.getHandler()).isNull();
        assertThat(list.getResponse().getStatus()).isEqualTo(404);
        assertThat(resolve.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void eventIdBasedTransactionalOutboxResolutionRemainsRegistered() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/outbox/event-1/resolve-confirmation")
                        .header("X-Idempotency-Key", "outbox-event-1-resolution")
                        .contentType("application/json")
                        .content("""
                                {
                                  "resolution": "PUBLISHED",
                                  "reason": "broker offset verified",
                                  "evidence_reference": {
                                    "type": "BROKER_OFFSET",
                                    "reference": "topic=fraud-decisions,partition=0,offset=42",
                                    "verified_at": "2026-10-01T08:00:00Z",
                                    "verified_by": "ops-admin"
                                  }
                                }
                                """))
                .andReturn();

        assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
        assertThat(((HandlerMethod) result.getHandler()).getMethod().getName()).isEqualTo("resolveConfirmation");
        verify(service).resolveConfirmation(
                eq("event-1"),
                any(OutboxConfirmationResolutionRequest.class),
                isNull(),
                eq("outbox-event-1-resolution")
        );
    }
}
