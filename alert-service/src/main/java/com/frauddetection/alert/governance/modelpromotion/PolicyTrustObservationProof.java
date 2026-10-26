package com.frauddetection.alert.governance.modelpromotion;

import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Startup-pinned workload report keys. A valid signature proves key possession, not runtime attestation. */
public final class PolicyTrustObservationProof {
    private static final String DOMAIN = "com.frauddetection.policy-trust.observation.v1";
    private final String deploymentId;
    private final Map<String, Entry> entries;

    private PolicyTrustObservationProof(String deploymentId, Map<String, Entry> entries) {
        this.deploymentId = deploymentId;
        this.entries = Map.copyOf(entries);
    }

    public static PolicyTrustObservationProof load(Path path) {
        try {
            var root = new ObjectMapper().readTree(Files.readString(path));
            if (!"policy-trust-observation-keys-v1".equals(root.path("version").asText())
                    || root.path("deploymentId").asText().isBlank()) {
                throw new SecurityException("Invalid observation key registry");
            }
            String deploymentId = root.path("deploymentId").asText();
            Map<String, Entry> entries = new HashMap<>();
            for (var item : root.path("keys")) {
                String role = item.path("role").asText();
                String instanceId = item.path("instanceId").asText();
                String keyId = item.path("keyId").asText();
                if (!("ALERT_VERIFIER".equals(role) || "PUBLISHER".equals(role))
                        || instanceId.isBlank() || keyId.isBlank()) {
                    throw new SecurityException("Invalid observation key binding");
                }
                byte[] der = Base64.getDecoder().decode(item.path("publicKeySpkiBase64").asText());
                PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(der));
                if (entries.putIfAbsent(keyId, new Entry(role, instanceId, publicKey)) != null) {
                    throw new SecurityException("Duplicate observation key ID");
                }
            }
            if (entries.isEmpty()) throw new SecurityException("Empty observation key registry");
            return new PolicyTrustObservationProof(deploymentId, entries);
        } catch (Exception failure) {
            throw new SecurityException("Observation key registry unavailable or invalid", failure);
        }
    }

    public static PrivateKey privateKey(Path path) {
        try {
            String encoded = Files.readString(path).trim();
            byte[] der = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(der).equals(encoded)) {
                throw new SecurityException("Noncanonical observation private key");
            }
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception failure) {
            throw new SecurityException("Observation private key unavailable or invalid", failure);
        }
    }

    public PolicyTrustInstanceObservation sign(PolicyTrustInstanceObservation report,
            String keyId, PrivateKey privateKey) {
        Entry entry = entries.get(keyId);
        if (entry == null || !entry.role().equals(report.role())
                || !entry.instanceId().equals(report.instanceId())
                || !deploymentId.equals(report.deploymentId())) {
            throw new SecurityException("Observation signing identity is not bound to this deployment");
        }
        try {
            var unsigned = new PolicyTrustInstanceObservation(report.id(), report.role(), report.instanceId(),
                    report.registry(), report.generation(), report.digestSha256(), report.observedAt(),
                    report.deploymentId(), report.processEpoch(), report.processStartedAt(), keyId, null);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(payload(unsigned));
            var signed = new PolicyTrustInstanceObservation(unsigned.id(), unsigned.role(), unsigned.instanceId(),
                    unsigned.registry(), unsigned.generation(), unsigned.digestSha256(), unsigned.observedAt(),
                    unsigned.deploymentId(), unsigned.processEpoch(), unsigned.processStartedAt(), keyId,
                    Base64.getEncoder().encodeToString(signer.sign()));
            if (!verify(signed)) throw new SecurityException("Observation private key does not match bound public key");
            return signed;
        } catch (Exception failure) {
            throw new SecurityException("Cannot sign workload observation", failure);
        }
    }

    public boolean verify(PolicyTrustInstanceObservation report) {
        if (report == null || report.proofKeyId() == null || report.signatureBase64() == null
                || !deploymentId.equals(report.deploymentId())) return false;
        Entry entry = entries.get(report.proofKeyId());
        if (entry == null || !entry.role().equals(report.role())
                || !entry.instanceId().equals(report.instanceId())) return false;
        try {
            byte[] signature = Base64.getDecoder().decode(report.signatureBase64());
            if (!Base64.getEncoder().encodeToString(signature).equals(report.signatureBase64())) return false;
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(entry.publicKey());
            verifier.update(payload(report));
            return verifier.verify(signature);
        } catch (Exception malformed) {
            return false;
        }
    }

    private static byte[] payload(PolicyTrustInstanceObservation report) throws IOException {
        if (report.registry() == null || report.observedAt() == null
                || report.processStartedAt() == null) throw new IllegalArgumentException("Incomplete observation");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (String value : new String[] { DOMAIN, report.id(), report.role(), report.instanceId(),
                report.deploymentId(), report.processEpoch(), report.processStartedAt().toString(),
                report.registry().name(), Long.toString(report.generation()), report.digestSha256(),
                report.observedAt().toString(), report.proofKeyId() }) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(encoded.length);
            out.write(encoded);
        }
        return bytes.toByteArray();
    }

    private record Entry(String role, String instanceId, PublicKey publicKey) { }
}
