/** Dedicated backend-owned authorities for institutional model policy workflows. */
export const MODEL_POLICY_AUTHORITIES = Object.freeze({
  READ: "model-policy:read",
  CREATE: "model-policy:create",
  SUBMIT: "model-policy:submit",
  APPROVE: "model-policy:approve",
  REJECT: "model-policy:reject",
  PUBLISH_REQUEST: "model-policy:publish:request",
  PUBLICATION_RECOVER: "model-policy:publication:recover",
  DIRECTORY_READ: "model-policy:directory:read",
  TRUST_READ: "model-policy:trust:read",
  TRUST_REQUEST: "model-policy:trust:request",
  TRUST_APPROVE: "model-policy:trust:approve",
  AUDIT_READ: "model-policy:audit:read",
  PRODUCTION: "model-policy:scope:production",
  CREDENTIAL_ENROLL: "model-policy:credential:enroll",
  CREDENTIAL_ADMIN: "model-policy:credential:admin"
});

export const MODEL_POLICY_AUTHORITY_PREFIX = "model-policy:";
