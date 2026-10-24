package com.frauddetection.alert.governance.modelpolicy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure canonical input for both live policy action signing and independent historical verification. */
public final class PolicyWebAuthnActionContract {
    public static final String DOMAIN = "com.frauddetection.model-policy.webauthn-action.v1";

    private PolicyWebAuthnActionContract() { }

    public static Map<String, Object> build(String challengeId, String nonce, Instant expiresAt,
            String issuer, String subject, String actorId, String operationId,
            String action, String decision, String rationale, String proposalId, long expectedVersion,
            String draftSha256, String policyId, long revision, String institutionalScope,
            String environment, String intendedUse) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", DOMAIN);
        contract.put("challengeId", challengeId);
        contract.put("nonce", nonce);
        contract.put("expiresAt", expiresAt.toString());
        contract.put("issuer", issuer);
        contract.put("subject", subject);
        contract.put("actorId", actorId);
        contract.put("operationId", operationId);
        contract.put("action", action);
        contract.put("decision", decision);
        if (rationale != null) contract.put("rationale", rationale);
        contract.put("proposalId", proposalId);
        contract.put("expectedVersion", expectedVersion);
        contract.put("draftSha256", draftSha256);
        contract.put("policyId", policyId);
        contract.put("revision", revision);
        contract.put("institutionalScope", institutionalScope);
        contract.put("environment", environment);
        contract.put("intendedUse", intendedUse);
        return contract;
    }
}
