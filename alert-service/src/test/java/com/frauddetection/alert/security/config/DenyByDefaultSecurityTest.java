package com.frauddetection.alert.security.config;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class DenyByDefaultSecurityTest extends AbstractSecurityRouteBoundaryWebMvcTest {

    @Test
    void unknownBackendLookingRouteFamiliesAreDenied() throws Exception {
        expectDenied(get("/api/v1/not-real").with(reader()));
        expectDenied(get("/api/not-real").with(reader()));
        expectDenied(get("/governance/not-real").with(reader()));
        expectDenied(get("/system/not-real").with(reader()));
        expectDenied(get("/bff/not-real").with(reader()));
        expectDenied(get("/actuator/not-real").with(reader()));
        expectDenied(get("/not-a-known-frontend-route").with(reader()));
    }
}
