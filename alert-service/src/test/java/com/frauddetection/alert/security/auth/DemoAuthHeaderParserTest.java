package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoAuthHeaderParserTest {

    private final DemoAuthHeaderParser parser = new DemoAuthHeaderParser();

    @Test
    void shouldReturnEmptyWhenDemoUserHeaderIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(parser.parse(request)).isEmpty();
    }

    @Test
    void shouldDefaultToReadOnlyAnalystWhenRolesAreMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DemoAuthHeaders.USER_ID, "analyst-1");

        var principal = parser.parse(request).orElseThrow();

        assertThat(principal.userId()).isEqualTo("analyst-1");
        assertThat(principal.roles()).containsExactly(AnalystRole.READ_ONLY_ANALYST);
        assertThat(principal.authorities())
                .contains(
                        AnalystAuthority.ALERT_READ,
                        AnalystAuthority.ASSISTANT_SUMMARY_READ,
                        AnalystAuthority.FRAUD_CASE_READ,
                        AnalystAuthority.TRANSACTION_MONITOR_READ
                )
                .doesNotContain(AnalystAuthority.ALERT_DECISION_SUBMIT);
    }

    @Test
    void shouldMapRolesAndAdditionalAuthorities() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DemoAuthHeaders.USER_ID, " analyst-2 ");
        request.addHeader(DemoAuthHeaders.ROLES, "ANALYST");
        request.addHeader(DemoAuthHeaders.AUTHORITIES, AnalystAuthority.FRAUD_CASE_UPDATE);

        var principal = parser.parse(request).orElseThrow();

        assertThat(principal.userId()).isEqualTo("analyst-2");
        assertThat(principal.roles()).containsExactly(AnalystRole.ANALYST);
        assertThat(principal.authorities())
                .contains(
                        AnalystAuthority.ALERT_DECISION_SUBMIT,
                        AnalystAuthority.FRAUD_CASE_UPDATE
                );
    }

    @Test
    void shouldRejectUnknownRoles() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DemoAuthHeaders.USER_ID, "analyst-1");
        request.addHeader(DemoAuthHeaders.ROLES, "UNKNOWN_ROLE");

        assertThatThrownBy(() -> parser.parse(request))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void shouldRejectUnknownAuthorities() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DemoAuthHeaders.USER_ID, "analyst-1");
        request.addHeader(DemoAuthHeaders.AUTHORITIES, "case:destroy");

        assertThatThrownBy(() -> parser.parse(request))
                .isInstanceOf(BadCredentialsException.class);
    }
}
