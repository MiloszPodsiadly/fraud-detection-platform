package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/** Offline entry point. A separately installed, trusted approval provider is required to publish. */
public final class ModelPromotionPolicyPublisherCli {

    private ModelPromotionPolicyPublisherCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        ObjectMapper mapper = new ObjectMapper();
        InstitutionalPolicyPublicationAuthorization authorization = approvalProvider();
        Path policyPath = Path.of(required(options, "--policy"));
        Path privateKeyPath = Path.of(required(options, "--private-key"));
        Path registryPath = Path.of(required(options, "--trusted-issuers"));
        String issuer = required(options, "--issuer");
        String keyId = required(options, "--key-id");
        ModelPromotionPolicyPublisher.UnsignedPolicy proposed = mapper.readValue(Files.readString(policyPath),
                ModelPromotionPolicyPublisher.UnsignedPolicy.class);
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(Files.readString(privateKeyPath).trim())));
        String generationFloor = System.getenv().getOrDefault("MODEL_PROMOTION_TRUST_MIN_GENERATION", "1");
        ModelPromotionTrustedIssuerRegistry registry = ModelPromotionTrustedIssuerRegistry.load(mapper, registryPath,
                Long.parseLong(generationFloor));
        ModelPromotionPolicyPublisher publisher = new ModelPromotionPolicyPublisher(mapper,
                new ModelPromotionPolicyValidator(mapper), authorization,
                new Ed25519ModelPromotionPolicyAuthorityVerifier(mapper, registry, Clock.systemUTC()),
                null, Clock.systemUTC());
        ModelPromotionPolicyPublisher.PreparedPublication prepared = publisher.prepare(
                proposed, privateKey, issuer, keyId);
        if (options.containsKey("--dry-run")) {
            System.out.println(mapper.writeValueAsString(Map.of(
                    "validated", true,
                    "published", false,
                    "policyId", prepared.policy().content().policyId(),
                    "revision", prepared.policy().content().revision(),
                    "canonicalSha256", prepared.policy().canonicalSha256())));
            return;
        }
        String mongoUri = System.getenv("MODEL_PROMOTION_PUBLISH_MONGODB_URI");
        if (mongoUri == null || mongoUri.isBlank()) {
            throw new IllegalStateException("MODEL_PROMOTION_PUBLISH_MONGODB_URI is required for publication");
        }
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(mongoUri);
        try {
            MongoModelPromotionPolicyPublicationStore store = new MongoModelPromotionPolicyPublicationStore(
                    new MongoTemplate(factory), new TransactionTemplate(new MongoTransactionManager(factory)));
            System.out.println(mapper.writeValueAsString(store.publish(prepared.policy(), Clock.systemUTC().instant())));
        } finally {
            factory.destroy();
        }
    }

    private static InstitutionalPolicyPublicationAuthorization approvalProvider() {
        List<InstitutionalPolicyPublicationAuthorization> providers = ServiceLoader.load(
                InstitutionalPolicyPublicationAuthorization.class).stream().map(ServiceLoader.Provider::get).toList();
        if (providers.size() != 1) {
            throw new SecurityException("Exactly one independently trusted publication authorization provider is required");
        }
        return providers.getFirst();
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if ("--dry-run".equals(option)) {
                if (result.putIfAbsent(option, "true") != null) {
                    throw new IllegalArgumentException("Duplicate option: " + option);
                }
            } else if (List.of("--policy", "--private-key", "--trusted-issuers", "--issuer", "--key-id")
                    .contains(option) && index + 1 < args.length) {
                if (result.putIfAbsent(option, args[++index]) != null) {
                    throw new IllegalArgumentException("Duplicate option: " + option);
                }
            } else {
                throw new IllegalArgumentException("Unsupported or incomplete publisher option: " + option);
            }
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
