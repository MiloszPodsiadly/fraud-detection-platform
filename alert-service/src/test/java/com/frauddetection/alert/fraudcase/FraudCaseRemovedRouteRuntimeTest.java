package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class FraudCaseRemovedRouteRuntimeTest {

    private FraudCaseManagementService fraudCaseManagementService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        fraudCaseManagementService = mock(FraudCaseManagementService.class);
        FraudCaseController controller = new FraudCaseController(
                fraudCaseManagementService,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                mock(AlertServiceMetrics.class),
                mock(SensitiveReadAuditService.class)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AlertServiceExceptionHandler())
                .build();
    }

    @ParameterizedTest(name = "{0} falls through removed FraudCase routing")
    @MethodSource("removedRoutes")
    void removedRoutesDoNotReachLegacyOrRetainedFraudCaseBehavior(String route, RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus())
                .as(route)
                .isIn(401, 403, 404, 405)
                .isNotEqualTo(410);
        assertThat(result.getHandler())
                .as(route + " must not resolve to a retained or retired FraudCase controller method")
                .isNull();
        assertThat(body)
                .doesNotContain("LEGACY_FRAUD_CASE_ROUTE_REMOVED")
                .doesNotContain("\"status\":410")
                .doesNotContain("\"error\":\"Gone\"");
        verifyNoInteractions(fraudCaseManagementService);
    }

    private static Stream<Arguments> removedRoutes() {
        return Stream.of(
                Arguments.of("POST /api/v1/fraud-cases", post("/api/v1/fraud-cases")),
                Arguments.of("POST /api/v1/fraud-cases/case-1/notes", post("/api/v1/fraud-cases/case-1/notes")),
                Arguments.of("GET /api/v1/fraud-cases/case-1/audit", get("/api/v1/fraud-cases/case-1/audit")),
                Arguments.of("GET /api/fraud-cases", get("/api/fraud-cases"))
        );
    }
}
