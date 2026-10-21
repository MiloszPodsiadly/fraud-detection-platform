package com.frauddetection.alert.governance.modelpolicy;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import java.util.Base64;
import java.util.stream.Collectors;

@Repository
public class PolicyWebAuthnCredentialRepository implements CredentialRepository {
    private final MongoTemplate mongo;

    public PolicyWebAuthnCredentialRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @PostConstruct
    void ensureIndexes() {
        mongo.indexOps(PolicyWebAuthnCredential.class).ensureIndex(new Index()
                .on("actorId", Sort.Direction.ASC).on("revoked", Sort.Direction.ASC)
                .named("policy_webauthn_owner_active_v1"));
        mongo.indexOps(PolicyWebAuthnCredential.class).ensureIndex(new Index()
                .on("userHandle", Sort.Direction.ASC).named("policy_webauthn_handle_v1"));
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return activeForActor(username).stream().map(credential -> PublicKeyCredentialDescriptor.builder()
                .id(bytes(credential.id())).build()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        if (username == null || !username.matches("^oidc:[0-9a-f]{64}$")) return Optional.empty();
        return Optional.of(new ByteArray(HexFormat.of().parseHex(username.substring(5))));
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        PolicyWebAuthnCredential credential = mongo.findOne(Query.query(Criteria.where("userHandle")
                .is(userHandle.getBase64Url()).and("revoked").is(false)), PolicyWebAuthnCredential.class);
        return Optional.ofNullable(credential).map(PolicyWebAuthnCredential::actorId);
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        PolicyWebAuthnCredential credential = mongo.findOne(Query.query(Criteria.where("_id")
                .is(credentialId.getBase64Url()).and("userHandle").is(userHandle.getBase64Url())
                .and("revoked").is(false)), PolicyWebAuthnCredential.class);
        return Optional.ofNullable(credential).map(this::registered);
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        PolicyWebAuthnCredential credential = mongo.findById(credentialId.getBase64Url(),
                PolicyWebAuthnCredential.class);
        return credential == null ? Set.of() : Set.of(registered(credential));
    }

    public PolicyWebAuthnCredential find(String credentialId) {
        return mongo.findById(credentialId, PolicyWebAuthnCredential.class);
    }

    public Set<PolicyWebAuthnCredential> activeForActor(String actorId) {
        return Set.copyOf(mongo.find(Query.query(Criteria.where("actorId").is(actorId)
                .and("revoked").is(false)), PolicyWebAuthnCredential.class));
    }

    private RegisteredCredential registered(PolicyWebAuthnCredential credential) {
        return RegisteredCredential.builder()
                .credentialId(bytes(credential.id()))
                .userHandle(bytes(credential.userHandle()))
                .publicKeyCose(bytes(credential.publicKeyCose()))
                .signatureCount(credential.signatureCount()).build();
    }

    private ByteArray bytes(String base64Url) {
        return new ByteArray(Base64.getUrlDecoder().decode(base64Url));
    }
}
