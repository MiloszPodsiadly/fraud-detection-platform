package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceVerificationProperties;


import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed, publicly known Ed25519 test vectors. Never imported by production code. */
final class TestSourceIssuer {
    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    static final byte[] PAYLOAD = "{\"transactionId\":\"txn-source-1\",\"amount\":\"42.00\"}".getBytes(StandardCharsets.UTF_8);
    static final String ISSUER = "source-fixture-issuer-only";
    static final String PURPOSE = "TRANSACTION_SOURCE_ATTESTATION";
    static final String AUDIENCE = "fraud-platform.ingest.v1";
    static final String[] SEEDS = {
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"
    };
    private static final String[] PUBLIC_KEYS = {
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
    };

    static SourceVerificationProperties.TrustedKey policy(int key, boolean revoked) {
        return new SourceVerificationProperties.TrustedKey(ISSUER, "institution-a", "source-a", "epoch-a",
                "key-" + (key + 1), "Ed25519", publicKey(key), NOW.minusSeconds(3600), NOW.plusSeconds(3600),
                revoked, PURPOSE, AUDIENCE, "policy-1");
    }

    static String publicKey(int key) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex("302a300506032b6570032100" + PUBLIC_KEYS[key]));
    }

    static byte[] proof() throws Exception { return proof(PAYLOAD, Map.of(), 0); }

    static byte[] proof(byte[] payload, Map<String, Object> overrides, int key) throws Exception {
        Map<String, Object> fields = claims(payload);
        fields.putAll(overrides);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                HexFormat.of().parseHex("302e020100300506032b657004220420" + SEEDS[key]))));
        signer.update(signedBytes(fields, payload));
        fields.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        return JsonMapper.builder().build().writeValueAsBytes(fields);
    }

    static Map<String, Object> claims(byte[] payload) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("protocolVersion", 1);
        fields.put("algorithm", "Ed25519");
        fields.put("issuer", ISSUER);
        fields.put("institution", "institution-a");
        fields.put("source", "source-a");
        fields.put("sourceEpoch", "epoch-a");
        fields.put("signingKeyVersion", "key-1");
        fields.put("sourceEventId", "source-event-42");
        fields.put("eventTime", NOW.minusSeconds(10).toString());
        fields.put("purpose", PURPOSE);
        fields.put("audience", AUDIENCE);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update("fraud-source-payload/v1\0".getBytes(StandardCharsets.US_ASCII));
        hash.update(java.nio.ByteBuffer.allocate(4).putInt(payload.length).array());
        fields.put("payloadCommitment", HexFormat.of().formatHex(hash.digest(payload)));
        return fields;
    }

    /** Independent reference encoder: does not call the production encoder or scope constructor. */
    static byte[] signedBytes(Map<String, Object> fields, byte[] payload) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        byte[] domain = "fraud-source-attestation/v1".getBytes(StandardCharsets.US_ASCII);
        out.writeInt(domain.length); out.write(domain);
        out.writeInt(((Number) fields.get("protocolVersion")).intValue());
        for (String name : new String[] {"algorithm", "issuer", "institution", "source", "sourceEpoch",
                "signingKeyVersion", "sourceEventId", "eventTime", "purpose", "audience", "payloadCommitment"}) {
            byte[] value = fields.get(name).toString().getBytes(StandardCharsets.UTF_8);
            out.writeInt(value.length); out.write(value);
        }
        out.writeInt(payload.length); out.write(payload);
        return buffer.toByteArray();
    }
}
