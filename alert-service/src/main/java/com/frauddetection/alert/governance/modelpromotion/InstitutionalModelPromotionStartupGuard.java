package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.runtime.InstitutionalModePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Prevents protected deployments from starting without model-promotion trust and durable persistence. */
@Component
public class InstitutionalModelPromotionStartupGuard implements ApplicationRunner {

    private final Environment environment;
    private final PolicyTrustRegistrySnapshotService registries;
    private final boolean persistenceEnabled;

    public InstitutionalModelPromotionStartupGuard(Environment environment,
            PolicyTrustRegistrySnapshotService registries,
            @Value("${app.model-promotion.persistence.enabled:true}") boolean persistenceEnabled) {
        this.environment = environment;
        this.registries = registries;
        this.persistenceEnabled = persistenceEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!InstitutionalModePolicy.isInstitutionalMode(environment)) return;
        if (!persistenceEnabled) {
            throw new IllegalStateException("Institutional model promotion requires persistence startup verification");
        }
        for (PolicyTrustRegistrySnapshotService.Registry registry
                : PolicyTrustRegistrySnapshotService.Registry.values()) {
            registries.requireReady(registry);
        }
    }
}
