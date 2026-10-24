package com.frauddetection.alert.security.authorization;

import java.util.Set;

/** Explicit IAM grants; deliberately absent from AnalystAuthority.ALL and analyst roles. */
public final class ModelPolicyAuthority {
    public static final String READ = "model-policy:read";
    public static final String CREATE = "model-policy:create";
    public static final String SUBMIT = "model-policy:submit";
    public static final String APPROVE = "model-policy:approve";
    public static final String REJECT = "model-policy:reject";
    public static final String PUBLISH_REQUEST = "model-policy:publish:request";
    public static final String PUBLICATION_RECOVER = "model-policy:publication:recover";
    public static final String DIRECTORY_READ = "model-policy:directory:read";
    public static final String TRUST_READ = "model-policy:trust:read";
    public static final String TRUST_REQUEST = "model-policy:trust:request";
    public static final String TRUST_APPROVE = "model-policy:trust:approve";
    public static final String AUDIT_READ = "model-policy:audit:read";
    public static final String PRODUCTION = "model-policy:scope:production";
    public static final String CREDENTIAL_ENROLL = "model-policy:credential:enroll";
    public static final String CREDENTIAL_ADMIN = "model-policy:credential:admin";
    public static final String CREDENTIAL_INCIDENT_REVOKE = "model-policy:credential:incident-revoke";

    public static final Set<String> ALL = Set.of(READ, CREATE, SUBMIT, APPROVE, REJECT,
            PUBLISH_REQUEST, PUBLICATION_RECOVER, DIRECTORY_READ, TRUST_READ, TRUST_REQUEST,
            TRUST_APPROVE, AUDIT_READ, PRODUCTION,
            CREDENTIAL_ENROLL, CREDENTIAL_ADMIN, CREDENTIAL_INCIDENT_REVOKE);

    private ModelPolicyAuthority() {
    }
}
