package com.frauddetection.trustauthority;

import com.frauddetection.trustauthority.api.TrustKeyResponse;
import com.frauddetection.trustauthority.api.TrustSignRequest;
import com.frauddetection.trustauthority.api.TrustSignResponse;
import com.frauddetection.trustauthority.api.TrustVerifyRequest;
import com.frauddetection.trustauthority.api.TrustVerifyResponse;

import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class TrustAuthorityService {

    private final TrustAuthorityProperties properties;
    private final TrustAuthorityAuditSink auditSink;
    private final TrustAuthorityRateLimiter rateLimiter;
    private final TrustAuthorityRequestReplayGuard replayGuard;
    private final TrustAuthorityMetrics metrics;
    private final TrustAuthorityRequestAuthorizer requestAuthorizer;
    private final TrustAuthorityKeyRegistry keyRegistry;
    private final TrustAuthoritySigningOperations signingOperations;
    private final Clock clock;

    @Autowired
    public TrustAuthorityService(
            TrustAuthorityProperties properties,
            TrustAuthorityAuditSink auditSink,
            TrustAuthorityRateLimiter rateLimiter,
            TrustAuthorityRequestReplayGuard replayGuard,
            TrustAuthorityMetrics metrics,
            Environment environment,
            Clock clock
    ) {
        this.properties = properties;
        this.auditSink = auditSink;
        this.rateLimiter = rateLimiter;
        this.replayGuard = replayGuard;
        this.metrics = metrics;
        this.requestAuthorizer = new TrustAuthorityRequestAuthorizer(properties, metrics, clock);
        this.keyRegistry = new TrustAuthorityKeyRegistry(properties, environment);
        this.signingOperations = new TrustAuthoritySigningOperations(
                keyRegistry, properties.getAuthorityName(), clock);
        this.clock = clock;
    }

    TrustAuthorityService(TrustAuthorityProperties properties, TrustAuthorityAuditSink auditSink, TrustAuthorityRateLimiter rateLimiter) {
        this.properties = properties;
        this.auditSink = auditSink;
        this.rateLimiter = rateLimiter;
        this.replayGuard = new TrustAuthorityRequestReplayGuard();
        this.metrics = new TrustAuthorityMetrics(Metrics.globalRegistry);
        this.requestAuthorizer = new TrustAuthorityRequestAuthorizer(properties, metrics, Clock.systemUTC());
        this.keyRegistry = new TrustAuthorityKeyRegistry(properties, true);
        this.signingOperations = new TrustAuthoritySigningOperations(
                keyRegistry, properties.getAuthorityName(), Clock.systemUTC());
        this.clock = Clock.systemUTC();
    }

    TrustSignResponse sign(TrustAuthorityRequestCredentials credentials, TrustSignRequest request) {
        CallerAuthorization authorization = requestAuthorizer.authorizeSign(credentials, request);
        TrustAuthorityCallerIdentity caller = authorization.identity();
        if (!authorization.allowed()) {
            audit("SIGN", caller, requestId(credentials), request.purpose(), request.payloadHash(), null, "FAILURE", authorization.reasonCode());
            metrics.recordSign("FAILURE");
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority signing caller is not authorized.");
        }
        if (!rateLimiter.allow(caller.serviceName(), authorization.signRateLimitPerMinute())) {
            audit("SIGN", caller, requestId(credentials), request.purpose(), request.payloadHash(), null, "FAILURE", "RATE_LIMIT_EXCEEDED");
            metrics.recordRateLimit();
            metrics.recordSign("FAILURE");
            throw new TrustAuthorityRequestException(HttpStatus.TOO_MANY_REQUESTS, "Trust authority signing rate limit exceeded.");
        }
        enforceReplay(caller, requestId(credentials), "SIGN", request.purpose(), request.payloadHash(), null);
        String failureReason = null;
        try {
            TrustSignResponse response = signingOperations.sign(request);
            audit("SIGN", caller, requestId(credentials), request.purpose(), request.payloadHash(), response.keyId(), "SUCCESS", null);
            metrics.recordSign("SUCCESS");
            return response;
        } catch (TrustAuthoritySigningOperations.OperationFailure exception) {
            failureReason = exception.reasonCode();
            throw exception.publicException();
        } finally {
            if (failureReason != null) {
                audit("SIGN", caller, requestId(credentials), request.purpose(), request.payloadHash(),
                        signingOperations.activeKeyId(), "FAILURE", failureReason);
                metrics.recordSign("FAILURE");
            }
        }
    }

    TrustVerifyResponse verify(TrustAuthorityRequestCredentials credentials, TrustVerifyRequest request) {
        CallerAuthorization authorization = requestAuthorizer.authorizeVerify(credentials, request);
        TrustAuthorityCallerIdentity caller = authorization.identity();
        if (!authorization.tokenValid()) {
            audit("VERIFY", caller, requestId(credentials), request.purpose(), request.payloadHash(), request.keyId(), "FAILURE", authorization.reasonCode());
            metrics.recordVerify("FAILURE");
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority verify caller is not authenticated.");
        }
        enforceReplay(caller, requestId(credentials), "VERIFY", request.purpose(), request.payloadHash(), request.keyId());
        TrustAuthoritySigningOperations.Verification verification = signingOperations.verify(request);
        if (verification.valid()) {
            audit("VERIFY", caller, requestId(credentials), request.purpose(), request.payloadHash(),
                    request.keyId(), "SUCCESS", null);
            metrics.recordVerify("SUCCESS");
            return new TrustVerifyResponse("VALID", null);
        }
        return verifyFailure(caller, requestId(credentials), request, verification.reasonCode());
    }

    List<TrustKeyResponse> keys() {
        return keyRegistry.responses();
    }

    TrustKeyResponse key() {
        return keyRegistry.activeResponse();
    }

    TrustAuthorityAuditIntegrityResponse auditIntegrity(TrustAuthorityRequestCredentials credentials, int limit) {
        return auditIntegrity(credentials, limit, "WINDOW");
    }

    TrustAuthorityAuditIntegrityResponse auditIntegrity(TrustAuthorityRequestCredentials credentials, int limit, String mode) {
        CallerAuthorization authorization = requestAuthorizer.authorizeAuditIntegrity(
                credentials, "trust-authority-audit-integrity");
        TrustAuthorityCallerIdentity caller = authorization.identity();
        if (!authorization.tokenValid()) {
            audit("VERIFY", caller, requestId(credentials), "AUDIT_INTEGRITY", "trust-authority-audit-integrity", null, "FAILURE", authorization.reasonCode());
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority audit integrity caller is not authenticated.");
        }
        enforceReplay(caller, requestId(credentials), "VERIFY", "AUDIT_INTEGRITY", "trust-authority-audit-integrity", null);
        TrustAuthorityAuditIntegrityResponse rawResponse = auditSink.integrity(limit, mode);
        TrustAuthorityAuditIntegrityResponse response = rawResponse.withDecisionTrace(decisionTrace(true, false, rawResponse));
        metrics.recordAuditIntegrityResult(response.status());
        audit("VERIFY", caller, requestId(credentials), "AUDIT_INTEGRITY", "trust-authority-audit-integrity", null,
                "VALID".equals(response.status()) ? "SUCCESS" : "FAILURE", response.reasonCode());
        return response;
    }

    TrustAuthorityAuditHeadResponse auditHead(TrustAuthorityRequestCredentials credentials) {
        CallerAuthorization authorization = requestAuthorizer.authorizeAuditIntegrity(
                credentials, "trust-authority-audit-head");
        TrustAuthorityCallerIdentity caller = authorization.identity();
        if (!authorization.tokenValid()) {
            audit("VERIFY", caller, requestId(credentials), "AUDIT_INTEGRITY", "trust-authority-audit-head", null, "FAILURE", authorization.reasonCode());
            throw new TrustAuthorityRequestException(authorization.status(), "Trust authority audit head caller is not authenticated.");
        }
        enforceReplay(caller, requestId(credentials), "VERIFY", "AUDIT_INTEGRITY", "trust-authority-audit-head", null);
        TrustAuthorityAuditHeadResponse response = auditSink.head();
        audit("VERIFY", caller, requestId(credentials), "AUDIT_INTEGRITY", "trust-authority-audit-head", null, "SUCCESS", null);
        return response;
    }

    private TrustVerifyResponse verifyFailure(TrustAuthorityCallerIdentity caller, String requestId, TrustVerifyRequest request, String reasonCode) {
        audit("VERIFY", caller, requestId, request.purpose(), request.payloadHash(), request.keyId(), "FAILURE", reasonCode);
        metrics.recordVerify("FAILURE");
        if ("SIGNATURE_INVALID".equals(reasonCode)) {
            metrics.recordInvalidSignature();
        } else if ("UNKNOWN_KEY".equals(reasonCode)) {
            metrics.recordUnknownKey();
        } else if ("KEY_REVOKED".equals(reasonCode)) {
            metrics.recordRevokedKey();
        }
        return new TrustVerifyResponse("INVALID", reasonCode);
    }

    private void audit(
            String action,
            TrustAuthorityCallerIdentity caller,
            String requestId,
            String purpose,
            String payloadHash,
            String keyId,
            String result,
            String reasonCode
    ) {
        TrustAuthorityCallerIdentity safeCaller = caller == null
                ? TrustAuthorityCallerIdentity.of(null, null, null)
                : caller;
        auditSink.append(new TrustAuthorityAuditEvent(
                TrustAuthorityAuditEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                action,
                safeCaller.auditIdentity(),
                safeCaller.serviceName(),
                requestId,
                purpose,
                payloadHash,
                keyId,
                result,
                reasonCode,
                clock.instant().truncatedTo(ChronoUnit.MILLIS),
                null,
                null,
                null
        ));
    }

    private void enforceReplay(
            TrustAuthorityCallerIdentity caller,
            String requestId,
            String action,
            String purpose,
            String payloadHash,
            String keyId
    ) {
        if (properties.getReplay().modeEnum() == TrustAuthorityReplayMode.DISTRIBUTED_HINT
                && StringUtils.hasText(requestId)
                && auditSink.requestSeen(caller.serviceName(), requestId)) {
            audit(action, caller, requestId, purpose, payloadHash, keyId, "FAILURE", "REPLAY_DETECTED");
            metrics.recordReplayDetected();
            if ("SIGN".equals(action)) {
                metrics.recordSign("FAILURE");
            } else {
                metrics.recordVerify("FAILURE");
            }
            throw new TrustAuthorityRequestException(HttpStatus.CONFLICT, "Trust authority request replay detected.");
        }
        if (!replayGuard.allow(caller.serviceName(), requestId)) {
            audit(action, caller, requestId, purpose, payloadHash, keyId, "FAILURE", "REPLAY_DETECTED");
            metrics.recordReplayDetected();
            if ("SIGN".equals(action)) {
                metrics.recordSign("FAILURE");
            } else {
                metrics.recordVerify("FAILURE");
            }
            throw new TrustAuthorityRequestException(HttpStatus.CONFLICT, "Trust authority request replay detected.");
        }
    }

    private TrustDecisionTrace decisionTrace(boolean identityVerified, boolean signatureVerified, TrustAuthorityAuditIntegrityResponse response) {
        return new TrustDecisionTrace(
                identityVerified,
                signatureVerified,
                chainTrace(response),
                "signingRequired=" + properties.isSigningRequired(),
                response.status()
        );
    }

    private String chainTrace(TrustAuthorityAuditIntegrityResponse response) {
        if ("PARTIAL".equals(response.status()) || response.integrityConfidence() == TrustAuthorityIntegrityConfidence.PARTIAL_BOUNDARY) {
            return "PARTIAL";
        }
        if (response.integrityConfidence() == TrustAuthorityIntegrityConfidence.FULL_CHAIN_VERIFIED) {
            return "FULL";
        }
        return "WINDOW";
    }

    private String requestId(TrustAuthorityRequestCredentials credentials) {
        return credentials == null ? null : credentials.requestId();
    }

}
