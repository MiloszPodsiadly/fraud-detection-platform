package com.frauddetection.alert.security.principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AnalystActorResolver {

    private static final Logger log = LoggerFactory.getLogger(AnalystActorResolver.class);

    private final CurrentAnalystUser currentAnalystUser;

    public AnalystActorResolver(CurrentAnalystUser currentAnalystUser) {
        this.currentAnalystUser = currentAnalystUser;
    }

    public String resolveActorId(String requestActorId, String action, String resourceId) {
        return currentAnalystUser.get()
                .map(principal -> principalActorId(principal, requestActorId, action, resourceId))
                .orElse(requestActorId);
    }

    private String principalActorId(AnalystPrincipal principal, String requestActorId, String action, String resourceId) {
        if (StringUtils.hasText(requestActorId) && !principal.userId().equals(requestActorId)) {
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
