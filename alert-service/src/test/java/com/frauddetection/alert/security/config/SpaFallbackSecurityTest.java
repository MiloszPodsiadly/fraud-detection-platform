package com.frauddetection.alert.security.config;

import org.junit.jupiter.api.Test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SpaFallbackSecurityTest extends AbstractSecurityRouteBoundaryWebMvcTest {

    @Test
    void frontendFallbackAllowsOnlyKnownGetRoutes() throws Exception {
        expectSecurityLayerDoesNotReject(get("/analyst-console"));
        expectSecurityLayerDoesNotReject(get("/fraud-case"));
        expectSecurityLayerDoesNotReject(get("/reports"));

        expectDenied(post("/analyst-console").with(reader()).with(csrf()));
        expectDenied(patch("/analyst-console").with(reader()).with(csrf()));
        expectDenied(delete("/analyst-console").with(reader()).with(csrf()));
    }

    @Test
    void frontendFallbackDoesNotCatchBackendLookingPaths() throws Exception {
        expectDenied(get("/api/anything").with(reader()));
        expectDenied(get("/api/v1/anything").with(reader()));
        expectDenied(get("/governance/anything").with(reader()));
        expectDenied(get("/system/anything").with(reader()));
        expectDenied(get("/bff/anything").with(reader()));
        expectDenied(get("/actuator/anything").with(reader()));
    }
}
