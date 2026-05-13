package com.frauddetection.alert.security.session;

import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
public class AnalystSessionController {

    private final CurrentAnalystUser currentAnalystUser;
    private final AlertServiceMetrics metrics;

    public AnalystSessionController(CurrentAnalystUser currentAnalystUser, AlertServiceMetrics metrics) {
        this.currentAnalystUser = currentAnalystUser;
        this.metrics = metrics;
    }

    @GetMapping("/api/v1/session")
    public ResponseEntity<AnalystSessionResponse> session(CsrfToken csrfToken) {
        boolean csrfPresent = csrfToken != null && StringUtils.hasText(csrfToken.getToken());
        AnalystSessionResponse response = currentAnalystUser.get()
                .map(principal -> new AnalystSessionResponse(
                        true,
                        "AUTHENTICATED",
                        requireUserId(principal.userId()),
                        principal.roles().stream().map(AnalystRole::name).sorted().toList(),
                        principal.authorities().stream().sorted().toList(),
                        csrf(csrfToken)
                ))
                .orElseGet(() -> new AnalystSessionResponse(false, "ANONYMOUS", "", List.of(), List.of(), csrf(csrfToken)));
        metrics.recordAnalystSessionRequest(response.authenticated() ? "authenticated" : "anonymous", csrfPresent);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    private String requireUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            metrics.recordAnalystSessionRequest("auth_error", false);
            metrics.recordAnalystSessionInvalidPrincipal("other");
            throw new ResponseStatusException(UNAUTHORIZED, "Authenticated session does not expose a usable subject.");
        }
        return userId.trim();
    }

    private CsrfResponse csrf(CsrfToken csrfToken) {
        if (csrfToken == null) {
            return null;
        }
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    public record AnalystSessionResponse(
            boolean authenticated,
            String sessionStatus,
            String userId,
            List<String> roles,
            List<String> authorities,
            CsrfResponse csrf
    ) {
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
