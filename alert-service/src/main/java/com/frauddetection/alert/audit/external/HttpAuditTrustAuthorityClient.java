package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class HttpAuditTrustAuthorityClient implements AuditTrustAuthorityClient {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final RestClient restClient;
    private final AuditTrustAuthorityProperties properties;

    HttpAuditTrustAuthorityClient(RestClient restClient, AuditTrustAuthorityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public SignedAuditAnchorPayload sign(AuditAnchorSigningPayload payload) {
        String payloadHash = payloadHash(payload);
        TrustSignRequest request = new TrustSignRequest(
                "AUDIT_ANCHOR",
                payloadHash,
                payload.partitionKey(),
                payload.chainPosition(),
                payload.localAnchorId()
        );
        try {
            TrustSignResponse response = restClient.post()
                    .uri("/api/v1/trust/sign")
                    .headers(headers -> internalHeaders(headers, "SIGN", signCredentialPayload(request)))
                    .body(request)
                    .retrieve()
                    .body(TrustSignResponse.class);
            if (response == null) {
                return SignedAuditAnchorPayload.unavailable();
            }
            return new SignedAuditAnchorPayload(
                    "SIGNED",
                    response.algorithm(),
                    response.signature(),
                    response.keyId(),
                    response.signedAt(),
                    response.authority(),
                    payloadHash
            );
        } catch (RestClientException exception) {
            return SignedAuditAnchorPayload.unavailable();
        }
    }

    @Override
    public List<AuditTrustAuthorityKey> keys() {
        try {
            List<AuditTrustAuthorityKey> keys = restClient.get()
                    .uri("/api/v1/trust/keys")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return keys == null ? List.of() : keys.stream().limit(20).toList();
        } catch (RestClientException exception) {
            return List.of();
        }
    }

    @Override
    public AuditTrustSignatureVerificationResult verify(AuditAnchorSigningPayload payload, SignedAuditAnchorPayload signature) {
        if (signature == null || signature.signature() == null || signature.keyId() == null) {
            return AuditTrustSignatureVerificationResult.unsigned();
        }
        String payloadHash = payloadHash(payload);
        TrustVerifyRequest request = new TrustVerifyRequest(
                "AUDIT_ANCHOR",
                payloadHash,
                payload.partitionKey(),
                payload.chainPosition(),
                payload.localAnchorId(),
                signature.signature(),
                signature.keyId(),
                signature.signedAt()
        );
        try {
            TrustVerifyResponse response = restClient.post()
                    .uri("/api/v1/trust/verify")
                    .headers(headers -> internalHeaders(headers, "VERIFY", verifyCredentialPayload(request)))
                    .body(request)
                    .retrieve()
                    .body(TrustVerifyResponse.class);
            if (response == null) {
                return AuditTrustSignatureVerificationResult.unavailable();
            }
            if ("VALID".equals(response.status())) {
                return AuditTrustSignatureVerificationResult.valid();
            }
            if ("UNKNOWN_KEY".equals(response.reasonCode())) {
                return AuditTrustSignatureVerificationResult.unknownKey();
            }
            if ("KEY_REVOKED".equals(response.reasonCode())) {
                return AuditTrustSignatureVerificationResult.keyRevoked();
            }
            return AuditTrustSignatureVerificationResult.invalid(response.reasonCode());
        } catch (RestClientException exception) {
            return AuditTrustSignatureVerificationResult.unavailable();
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    private String payloadHash(AuditAnchorSigningPayload payload) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("chain_position", payload.chainPosition());
        canonical.put("external_hash", payload.externalHash());
        canonical.put("external_key", payload.externalKey());
        canonical.put("immutability_level", payload.immutabilityLevel() == null ? ExternalImmutabilityLevel.NONE.name() : payload.immutabilityLevel().name());
        canonical.put("last_event_hash", payload.lastEventHash());
        canonical.put("local_anchor_id", payload.localAnchorId());
        canonical.put("partition_key", payload.partitionKey());
        try {
            byte[] serialized = CANONICAL_JSON.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Audit anchor signing payload could not be hashed.");
        }
    }

    private void internalHeaders(org.springframework.http.HttpHeaders headers, String action, String payload) {
        String requestId = UUID.randomUUID().toString();
        headers.set("X-Internal-Trust-Request-Id", requestId);
        if ("JWT_SERVICE_IDENTITY".equals(normalizedIdentityMode())) {
            headers.setBearerAuth(jwtToken());
            return;
        }
        String signedAt = Instant.now().toString();
        headers.set("X-Internal-Service-Name", properties.getCallerServiceName());
        headers.set("X-Internal-Service-Environment", properties.getCallerEnvironment());
        headers.set("X-Internal-Trust-Signed-At", signedAt);
        headers.set("X-Internal-Trust-Signature", hmac(properties.getHmacSecret(), credentialPayload(action, requestId, signedAt, payload)));
        if (StringUtils.hasText(properties.getCallerInstanceId())) {
            headers.set("X-Internal-Service-Instance-Id", properties.getCallerInstanceId());
        }
    }

    private String normalizedIdentityMode() {
        return properties.getIdentityMode() == null ? "HMAC_LOCAL" : properties.getIdentityMode().trim().replace('-', '_').toUpperCase();
    }

    private String jwtToken() {
        try {
            AuditTrustAuthorityProperties.JwtIdentity jwt = properties.getJwtIdentity();
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(jwt.getIssuer())
                    .audience(jwt.getAudience())
                    .subject(properties.getCallerServiceName())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(jwt.getTtl())))
                    .claim("service_name", properties.getCallerServiceName())
                    .claim("authorities", authorities(jwt.getAuthorities()))
                    .build();
            SignedJWT signedJWT = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(jwt.getKeyId())
                            .build(),
                    claims
            );
            signedJWT.sign(new RSASSASigner(loadPrivateKey(jwt)));
            return signedJWT.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Audit trust authority JWT credentials could not be created.");
        }
    }

    private RSAPrivateKey loadPrivateKey(AuditTrustAuthorityProperties.JwtIdentity jwt) throws Exception {
        String material = jwt.getPrivateKey();
        if (!StringUtils.hasText(material) && StringUtils.hasText(jwt.getPrivateKeyPath())) {
            material = Files.readString(Path.of(jwt.getPrivateKeyPath()));
        }
        String normalized = material
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(normalized);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private List<String> authorities(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split("[,\\s]+"))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String signCredentialPayload(TrustSignRequest request) {
        return String.join("\n",
                nullSafe(request.purpose()),
                nullSafe(request.payloadHash()),
                nullSafe(request.partitionKey()),
                Long.toString(request.chainPosition()),
                nullSafe(request.anchorId()));
    }

    private String verifyCredentialPayload(TrustVerifyRequest request) {
        return String.join("\n",
                nullSafe(request.purpose()),
                nullSafe(request.payloadHash()),
                nullSafe(request.partitionKey()),
                Long.toString(request.chainPosition()),
                nullSafe(request.anchorId()),
                nullSafe(request.signature()),
                nullSafe(request.keyId()),
                request.signedAt() == null ? "" : request.signedAt().toString());
    }

    private String credentialPayload(String action, String requestId, String signedAt, String payload) {
        return String.join("\n",
                action,
                nullSafe(properties.getCallerServiceName()),
                nullSafe(properties.getCallerEnvironment()),
                nullSafe(requestId),
                nullSafe(signedAt),
                payload);
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Audit trust authority request credentials could not be created.");
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record TrustSignRequest(
            @JsonProperty("purpose") String purpose,
            @JsonProperty("payload_hash") String payloadHash,
            @JsonProperty("partition_key") String partitionKey,
            @JsonProperty("chain_position") long chainPosition,
            @JsonProperty("anchor_id") String anchorId
    ) {
    }

    private record TrustSignResponse(
            @JsonProperty("signature") String signature,
            @JsonProperty("key_id") String keyId,
            @JsonProperty("algorithm") String algorithm,
            @JsonProperty("signed_at") Instant signedAt,
            @JsonProperty("authority") String authority
    ) {
    }

    private record TrustVerifyRequest(
            @JsonProperty("purpose") String purpose,
            @JsonProperty("payload_hash") String payloadHash,
            @JsonProperty("partition_key") String partitionKey,
            @JsonProperty("chain_position") long chainPosition,
            @JsonProperty("anchor_id") String anchorId,
            @JsonProperty("signature") String signature,
            @JsonProperty("key_id") String keyId,
            @JsonProperty("signed_at") Instant signedAt
    ) {
    }

    private record TrustVerifyResponse(
            @JsonProperty("status") String status,
            @JsonProperty("reason_code") String reasonCode
    ) {
    }
}
