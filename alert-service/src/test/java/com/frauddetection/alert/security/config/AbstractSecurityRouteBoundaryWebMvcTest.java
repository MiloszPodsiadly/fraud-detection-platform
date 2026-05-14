package com.frauddetection.alert.security.config;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.auth.OidcAnalystAuthoritiesMapper;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.security.session.AnalystSessionController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@WebMvcTest(AnalystSessionController.class)
@Import({
        AlertSecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        CurrentAnalystUser.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.bff.enabled=true",
        "app.security.bff.provider-logout-uri=http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout",
        "app.security.bff.post-logout-redirect-uri=http://localhost:4173/",
        "app.security.bff.client-id=analyst-console-ui"
})
abstract class AbstractSecurityRouteBoundaryWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AlertServiceMetrics alertServiceMetrics;

    @MockBean
    OidcAnalystAuthoritiesMapper oidcAnalystAuthoritiesMapper;

    @MockBean
    ClientRegistrationRepository clientRegistrationRepository;

    RequestPostProcessor userWith(String... authorities) {
        return authentication(new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        ));
    }

    RequestPostProcessor reader() {
        return userWith(AnalystAuthority.FRAUD_CASE_READ);
    }

    void expectSecurityLayerDoesNotReject(MockHttpServletRequestBuilder request) throws Exception {
        // This only proves Spring Security did not return 401/403; MVC handler success is tested separately.
        ResultActions result = mockMvc.perform(request);
        result.andExpect(response -> assertThat(response.getResponse().getStatus())
                .isNotIn(401, 403));
    }

    void expectDenied(MockHttpServletRequestBuilder request) throws Exception {
        ResultActions result = mockMvc.perform(request);
        result.andExpect(response -> assertThat(response.getResponse().getStatus())
                .isIn(401, 403));
    }
}
