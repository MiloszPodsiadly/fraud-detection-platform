package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Exact, short-lived first-install authority from a protected operator-mounted file. */
@Component
public class PolicyCredentialBootstrapAuthority {
    private final ObjectMapper mapper;
    private final String path;

    public PolicyCredentialBootstrapAuthority(ObjectMapper mapper,
            @Value("${app.model-promotion.credential-bootstrap.path:}") String path) {
        this.mapper = mapper;
        this.path = path;
    }

    public Authorization require(PolicyCredentialEnrollmentGrantDocument grant,
            String reviewerId, Instant now) {
        if (path == null || path.isBlank())
            throw new AccessDeniedException("Protected first-install authority unavailable");
        try {
            var file = Path.of(path);
            if (!Files.isRegularFile(file) || !Files.isReadable(file))
                throw new AccessDeniedException("Protected first-install authority unavailable");
            var root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject() || root.properties().size() != 12)
                throw new AccessDeniedException("Bootstrap authorization schema invalid");
            var auth = mapper.treeToValue(root, Authorization.class);
            if (!Authorization.VERSION.equals(auth.version())
                    || !validId(auth.authorizationId()) || !grant.id().equals(auth.requestId())
                    || grant.purpose() != PolicyCredentialEnrollmentGrantDocument.Purpose.FIRST
                    || !grant.targetIssuer().equals(auth.targetIssuer())
                    || !grant.targetSubject().equals(auth.targetSubject())
                    || !grant.requestedBy().equals(auth.requestedBy())
                    || !reviewerId.equals(auth.approvedBy())
                    || !grant.caseReference().equals(auth.caseReference())
                    || !grant.requestExpiresAt().equals(Instant.parse(auth.requestExpiresAt()))
                    || auth.installationAuthority() == null || auth.installationAuthority().isBlank()
                    || auth.independentAuthorizer() == null || auth.independentAuthorizer().isBlank()
                    || auth.installationAuthority().equals(auth.independentAuthorizer())
                    || auth.installationAuthority().equals(reviewerId)
                    || auth.independentAuthorizer().equals(reviewerId)
                    || auth.installationAuthority().equals(grant.requestedBy())
                    || auth.independentAuthorizer().equals(grant.requestedBy())
                    || auth.installationAuthority().equals(grant.targetActorId())
                    || auth.independentAuthorizer().equals(grant.targetActorId())
                    || !now.isBefore(Instant.parse(auth.expiresAt()))
                    || Instant.parse(auth.expiresAt()).isAfter(grant.requestExpiresAt()))
                throw new AccessDeniedException("Bootstrap authorization does not match exact first-install grant");
            return auth;
        } catch (AccessDeniedException denied) {
            throw denied;
        } catch (Exception invalid) {
            throw new AccessDeniedException("Protected bootstrap authorization invalid", invalid);
        }
    }

    private boolean validId(String id) {
        return id != null && id.matches("^[A-Za-z0-9._:-]{1,128}$");
    }

    public record Authorization(String version, String authorizationId, String requestId,
            String targetIssuer, String targetSubject, String requestedBy, String approvedBy,
            String caseReference, String requestExpiresAt, String expiresAt,
            String installationAuthority, String independentAuthorizer) {
        public static final String VERSION = "policy-credential-bootstrap-v1";
    }
}
