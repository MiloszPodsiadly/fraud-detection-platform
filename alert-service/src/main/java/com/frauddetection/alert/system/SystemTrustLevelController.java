package com.frauddetection.alert.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemTrustLevelController implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemTrustLevelController.class);

    private final boolean publicationEnabled;
    private final boolean publicationRequired;
    private final boolean failClosed;
    private final boolean trustAuthorityEnabled;
    private final boolean signingRequired;

    public SystemTrustLevelController(
            @Value("${app.audit.external-anchoring.publication.enabled:${app.audit.external-anchoring.enabled:false}}") boolean publicationEnabled,
            @Value("${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}") boolean publicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}}") boolean failClosed,
            @Value("${app.audit.trust-authority.enabled:false}") boolean trustAuthorityEnabled,
            @Value("${app.audit.trust-authority.signing-required:false}") boolean signingRequired
    ) {
        this.publicationEnabled = publicationEnabled;
        this.publicationRequired = publicationRequired;
        this.failClosed = failClosed;
        this.trustAuthorityEnabled = trustAuthorityEnabled;
        this.signingRequired = signingRequired;
    }

    @GetMapping("/system/trust-level")
    public SystemTrustLevelResponse trustLevel() {
        return new SystemTrustLevelResponse(
                guaranteeLevel(),
                publicationEnabled,
                publicationRequired,
                failClosed,
                externalAnchorStrength()
        );
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("FDP24_FAIL_CLOSED".equals(guaranteeLevel())) {
            log.info("FDP-24 FAIL-CLOSED MODE ACTIVE");
        }
    }

    private String guaranteeLevel() {
        return publicationRequired && failClosed ? "FDP24_FAIL_CLOSED" : "BEST_EFFORT";
    }

    private String externalAnchorStrength() {
        return trustAuthorityEnabled && signingRequired ? "SIGNED_EXTERNAL" : "UNSIGNED_EXTERNAL";
    }
}
