package com.frauddetection.alert.security.principal;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AnalystActorResolver {

    private static final Logger log = LoggerFactory.getLogger(AnalystActorResolver.class);

    private final CurrentAnalystUser currentAnalystUser;
    private final AlertServiceMetrics metrics;

    public AnalystActorResolver(CurrentAnalystUser currentAnalystUser, AlertServiceMetrics metrics) {
        this.currentAnalystUser = currentAnalystUser;
        this.metrics = metrics;
    }

    public String resolveActorId(String requestActorId, String action, String resourceId) {
        return currentAnalystUser.get()
                .map(principal -> principalActorId(principal, requestActorId, action, resourceId))
                .orElse(requestActorId);
    }

    private String principalActorId(AnalystPrincipal principal, String requestActorId, String action, String resourceId) {
        if (StringUtils.hasText(requestActorId) && !principal.userId().equals(requestActorId)) {
            metrics.recordActorMismatch(action);
            log.warn(
                    "Request actor id differs from authenticated principal; using principal as actor. action={} resourceId={} principalUserId={} requestActorId={}",
                    action,
                    resourceId,
                    principal.userId(),
                    requestActorId
            );
        }
        return principal.userId();
    }
}
