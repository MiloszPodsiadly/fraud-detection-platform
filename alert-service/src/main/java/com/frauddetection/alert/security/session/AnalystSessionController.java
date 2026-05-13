package com.frauddetection.alert.security.session;

import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AnalystSessionController {

    private final CurrentAnalystUser currentAnalystUser;

    public AnalystSessionController(CurrentAnalystUser currentAnalystUser) {
        this.currentAnalystUser = currentAnalystUser;
    }

    @GetMapping("/api/v1/session")
    public AnalystSessionResponse session(CsrfToken csrfToken) {
        return currentAnalystUser.get()
                .map(principal -> new AnalystSessionResponse(
                        true,
                        principal.userId(),
                        principal.roles().stream().map(AnalystRole::name).sorted().toList(),
                        principal.authorities().stream().sorted().toList(),
                        csrf(csrfToken)
                ))
                .orElseGet(() -> new AnalystSessionResponse(false, "", List.of(), List.of(), csrf(csrfToken)));
    }

    private CsrfResponse csrf(CsrfToken csrfToken) {
        if (csrfToken == null) {
            return null;
        }
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    public record AnalystSessionResponse(
            boolean authenticated,
            String userId,
            List<String> roles,
            List<String> authorities,
            CsrfResponse csrf
    ) {
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
