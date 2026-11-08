package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.frauddetection.common.events.source.SourceLineage.State.*;
import static com.frauddetection.common.events.source.SourceLineageSchema.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** Private sidecar protocol. Service authorization comes from authenticated, restricted Mongo credentials,
 * source authenticity from the ingest-owned admitted receipt, and permission from the current issuer policy.
 * A UUID, event hash or existing link alone never produces a verified result.
 */
public final class MongoSourceLineage implements SourceLineageAccess {
    public enum Stage { INGEST, ENRICHER, SCORER }
    private static final String IDENTITIES = "source_admission_identities";
    private static final String RECEIPTS = "source_admission_receipts";
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.MAJORITY).readPreference(ReadPreference.primary()).build();
    private static final Set<String> FEATURES = Set.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT,
            FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW,
            FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN,
            FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE,
            FraudFeatureContract.MERCHANT_FREQUENCY_7D, FraudFeatureContract.DEVICE_NOVELTY, FraudFeatureContract.COUNTRY_MISMATCH,
            FraudFeatureContract.PROXY_OR_VPN_DETECTED, FraudFeatureContract.CUSTOMER_SEGMENT,
            FraudFeatureContract.MERCHANT_CATEGORY, FraudFeatureContract.CURRENCY);
    private final MongoClient client;
    private final MongoDatabase database;
    private final SourceSignatureVerifier policy;
    private final Stage stage;
    private final Clock clock;
    private boolean occurrenceWrites;

    public MongoSourceLineage(MongoClient client, String database, String expectedUser, Stage stage,
                              SourceSignatureVerifier policy, Clock clock) {
        this.client = Objects.requireNonNull(client);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.stage = Objects.requireNonNull(stage);
        if (database == null || !database.matches("[a-z][a-z0-9_]{0,63}")) { throw invalid("DATABASE_INVALID"); }
        this.database = client.getDatabase(database).withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY).withReadPreference(ReadPreference.primary());
        Long timeout = this.database.getTimeout(TimeUnit.MILLISECONDS);
        if (timeout == null || timeout <= 0 || timeout > 5_000) { throw invalid("OPERATION_TIMEOUT_REQUIRED"); }
        requireServicePrincipal(expectedUser);
        SourceLineageSchema.initialize(this.database, stage == Stage.INGEST);
        if (stage == Stage.INGEST) { SourceOccurrenceSchema.initialize(this.database, true); }
    }

    private void requireServicePrincipal(String expectedUser) {
        SourceScope.requireReference(expectedUser);
        Document auth = database.runCommand(new Document("connectionStatus", 1).append("showPrivileges", true))
                .get("authInfo", Document.class);
        List<Document> users = auth == null ? null : auth.getList("authenticatedUsers", Document.class);
        if (users == null || users.size() != 1 || !expectedUser.equals(users.getFirst().getString("user"))) {
            throw invalid("SERVICE_AUTHENTICATION_REQUIRED");
        }
        if (stage == Stage.INGEST) { return; } // Only the source owner can bootstrap; bindRaw still requires genuine Verified evidence.
        List<Document> privileges = auth.getList("authenticatedUserPrivileges", Document.class);
        if (privileges == null) { throw invalid("SERVICE_AUTHORIZATION_REQUIRED"); }
        Set<String> read = new java.util.HashSet<>();
        Set<String> insert = new java.util.HashSet<>();
        Set<String> update = new java.util.HashSet<>();
        Set<String> allowed = Set.of("find", "listCollections", "listIndexes", "insert", "update");
        for (Document privilege : privileges) {
            Document resource = privilege.get("resource", Document.class);
            List<String> actions = privilege.getList("actions", String.class);
            if (resource == null || actions == null || !database.getName().equals(resource.getString("db"))
                    || !allowed.containsAll(actions)) { throw invalid("SERVICE_PRIVILEGES_TOO_BROAD"); }
            String collection = resource.getString("collection");
            if (collection == null || collection.isEmpty() && !actions.stream().allMatch("listCollections"::equals)
                    || !collection.isEmpty() && !Set.of(IDENTITIES, RECEIPTS, RAW, ENRICHED, RAW_COUNTS, ENRICHED_COUNTS,
                    SourceOccurrenceSchema.OCCURRENCES, SourceOccurrenceSchema.COUNTS).contains(collection)) {
                throw invalid("SERVICE_PRIVILEGES_TOO_BROAD");
            }
            for (String action : actions) {
                if (action.equals("find")) { read.add(collection); }
                if (action.equals("insert")) { insert.add(collection); }
                if (action.equals("update")) { update.add(collection); }
                boolean permittedWrite = stage == Stage.ENRICHER && Set.of(ENRICHED, ENRICHED_COUNTS).contains(collection)
                        || stage == Stage.SCORER && Set.of(SourceOccurrenceSchema.OCCURRENCES, SourceOccurrenceSchema.COUNTS).contains(collection);
                if ((action.equals("insert") || action.equals("update")) && !permittedWrite) {
                    throw invalid("SERVICE_WRITE_NOT_AUTHORIZED");
                }
            }
        }
        if (!read.containsAll(Set.of(IDENTITIES, RECEIPTS, RAW, ENRICHED))) { throw invalid("SERVICE_READ_NOT_AUTHORIZED"); }
        if (stage == Stage.ENRICHER && (!read.contains(ENRICHED_COUNTS)
                || !insert.containsAll(Set.of(ENRICHED, ENRICHED_COUNTS)) || !update.containsAll(Set.of(ENRICHED, ENRICHED_COUNTS)))) {
            throw invalid("SERVICE_WRITE_NOT_AUTHORIZED");
        }
        occurrenceWrites = stage == Stage.SCORER && read.containsAll(SourceOccurrenceSchema.COLLECTIONS)
                && insert.containsAll(SourceOccurrenceSchema.COLLECTIONS) && update.containsAll(SourceOccurrenceSchema.COLLECTIONS);
    }

    void requireOccurrenceWriter() {
        if (!occurrenceWrites) { throw invalid("OCCURRENCE_WRITE_NOT_AUTHORIZED"); }
    }

    /** Same authenticated durable chain validation as live processing, used only for exact private recovery. */
    SourceLineage recoverEnriched(String receiptId, String commitment) {
        return transaction(session -> {
            Document link = database.getCollection(ENRICHED).find(session, eq("eventCommitment", commitment)).first();
            if (link == null || !receiptId.equals(link.getString("receiptId"))) { return absent(UNAVAILABLE); }
            return enrichedLink(session, link, commitment);
        });
    }

    Instant receiptExpiry(String receiptId) {
        return database.getCollection(RECEIPTS).find(eq("_id", receiptId)).first().getDate("expiresAt").toInstant();
    }

    public SourceLineage bindRaw(SourceSignatureVerifier.Verified evidence, String receiptId, TransactionRawEvent event) {
        if (stage != Stage.INGEST || evidence == null || policy.currentDenial(evidence) != null
                || !SourceReceiptIdentity.identity(evidence.scope()).equals(receiptId)) { return absent(REVOKED); }
        return transaction(session -> {
            Receipt receipt = receipt(session, receiptId);
            if (receipt.state != VERIFIED) { return absent(receipt.state); }
            if (!evidence.scope().equals(receipt.scope) || !evidence.keyCommitment().equals(receipt.keyCommitment)
                    || !evidence.policyVersion().equals(receipt.policyVersion)) { return absent(REVOKED); }
            return bind(session, RAW, RAW_COUNTS, event.eventId(), event.createdAt(), SourceEventCommitment.raw(event),
                    receipt, SourceReceiptIdentity.binding(receipt.scope, receipt.keyCommitment));
        });
    }

    @Override public SourceLineage raw(TransactionRawEvent event) {
        return transaction(session -> raw(session, event));
    }
    private SourceLineage raw(ClientSession session, TransactionRawEvent event) {
        String commitment = SourceEventCommitment.raw(event);
        Document link = database.getCollection(RAW).find(session,
                eq("_id", SourceEventCommitment.eventIdentity("raw", event.eventId(), event.createdAt()))).first();
        if (link == null) { return absent(UNVERIFIED); }
        if (!validLink(link, event.eventId(), event.createdAt(), commitment)) { return absent(CONFLICT); }
        Receipt receipt = receipt(session, link.getString("receiptId"));
        if (receipt.state != VERIFIED) { return absent(receipt.state); }
        if (!SourceReceiptIdentity.binding(receipt.scope, receipt.keyCommitment).equals(link.getString("parentCommitment"))
                || !receipt.expiresAt.equals(link.getDate("expiresAt").toInstant())) { return absent(CONFLICT); }
        return SourceLineage.verified(receipt.id, receipt.scope, commitment);
    }

    @Override public SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output) {
        if (stage != Stage.ENRICHER) { return absent(UNAVAILABLE); }
        return transaction(session -> {
            SourceLineage parent = raw(session, input);
            if (parent.state() != VERIFIED) { return parent; }
            if (!sameInterpretation(input, output) || output.featureSnapshot() == null
                    || !FEATURES.equals(output.featureSnapshot().keySet())) { return absent(CONFLICT); }
            Receipt receipt = receipt(session, parent.receiptId());
            if (receipt.state != VERIFIED) { return absent(receipt.state); }
            return bind(session, ENRICHED, ENRICHED_COUNTS, output.eventId(), output.createdAt(),
                    SourceEventCommitment.enriched(output), receipt, parent.eventCommitment());
        });
    }

    @Override public SourceLineage enriched(TransactionEnrichedEvent event) {
        return transaction(session -> {
            String commitment = SourceEventCommitment.enriched(event);
            Document link = database.getCollection(ENRICHED).find(session,
                    eq("_id", SourceEventCommitment.eventIdentity("enriched", event.eventId(), event.createdAt()))).first();
            if (link == null) { return absent(UNVERIFIED); }
            if (!validLink(link, event.eventId(), event.createdAt(), commitment)) { return absent(CONFLICT); }
            return enrichedLink(session, link, commitment);
        });
    }

    private SourceLineage enrichedLink(ClientSession session, Document link, String commitment) {
        if (!validLink(link, link.getString("eventId"), canonicalTime(link.getString("eventCreatedAt")), commitment)
                || !SourceEventCommitment.eventIdentity("enriched", link.getString("eventId"), canonicalTime(link.getString("eventCreatedAt")))
                .equals(link.getString("_id"))) { return absent(CONFLICT); }
        Document raw = database.getCollection(RAW).find(session, eq("eventCommitment", link.getString("parentCommitment"))).first();
        if (raw == null || raw.getInteger("schemaVersion", 0) != 1 || !"BOUND".equals(raw.getString("state"))
                || !SourceEventCommitment.eventIdentity("raw", raw.getString("eventId"), canonicalTime(raw.getString("eventCreatedAt")))
                .equals(raw.getString("_id")) || !Objects.equals(raw.get("receiptId"), link.get("receiptId"))) {
            return absent(UNAVAILABLE);
        }
        Receipt receipt = receipt(session, link.getString("receiptId"));
        if (receipt.state != VERIFIED) { return absent(receipt.state); }
        if (!SourceReceiptIdentity.binding(receipt.scope, receipt.keyCommitment).equals(raw.getString("parentCommitment"))
                || !receipt.expiresAt.equals(raw.getDate("expiresAt").toInstant())
                || !receipt.expiresAt.equals(link.getDate("expiresAt").toInstant())) { return absent(CONFLICT); }
        return SourceLineage.verified(receipt.id, receipt.scope, commitment);
    }

    private SourceLineage bind(ClientSession session, String name, String counts, String eventId, Instant createdAt,
                               String commitment, Receipt receipt, String parent) {
        String kind = name.equals(RAW) ? "raw" : "enriched";
        String id = SourceEventCommitment.eventIdentity(kind, eventId, createdAt);
        MongoCollection<Document> collection = database.getCollection(name);
        Document existing = collection.find(session, eq("_id", id)).first();
        if (existing != null) {
            if (validLink(existing, eventId, createdAt, commitment) && receipt.id.equals(existing.getString("receiptId"))
                    && parent.equals(existing.getString("parentCommitment"))) {
                return SourceLineage.verified(receipt.id, receipt.scope, commitment);
            }
            if (!collection.updateOne(session, eq("_id", id), set("state", "CONFLICT")).wasAcknowledged()) {
                throw invalid("WRITE_UNRESOLVED");
            }
            return absent(CONFLICT);
        }
        MongoCollection<Document> counter = database.getCollection(counts);
        Document count = counter.find(session, eq("_id", receipt.id)).first();
        int existingLinks = collection.find(session, eq("receiptId", receipt.id)).projection(new Document("_id", 1))
                .hintString(SOURCE_LINK_INDEX).limit(MAX_LINKS_PER_SOURCE + 1).into(new java.util.ArrayList<>()).size();
        if (existingLinks > MAX_LINKS_PER_SOURCE || (count == null ? existingLinks != 0
                : count.getInteger("schemaVersion", 0) != 1 || count.getInteger("count", 0) != existingLinks)) {
            return absent(UNAVAILABLE);
        }
        if (count == null) { counter.insertOne(session, new Document("_id", receipt.id).append("schemaVersion", 1).append("count", 1)); }
        else if (count.getInteger("count") >= MAX_LINKS_PER_SOURCE) { return absent(UNAVAILABLE); }
        else if (counter.updateOne(session, and(eq("_id", receipt.id), lt("count", MAX_LINKS_PER_SOURCE)), inc("count", 1)).getModifiedCount() != 1) {
            throw invalid("CAPACITY_UNRESOLVED");
        }
        Document candidate = new Document("_id", id).append("schemaVersion", 1).append("eventId", eventId)
                .append("eventCreatedAt", createdAt.toString()).append("eventCommitment", commitment).append("receiptId", receipt.id)
                .append("parentCommitment", parent).append("expiresAt", Date.from(receipt.expiresAt)).append("state", "BOUND");
        if (!collection.insertOne(session, candidate).wasAcknowledged()) { throw invalid("WRITE_UNRESOLVED"); }
        return SourceLineage.verified(receipt.id, receipt.scope, commitment);
    }

    private Receipt receipt(ClientSession session, String id) {
        Document identity = database.getCollection(IDENTITIES).find(session, eq("_id", id)).first();
        Document row = database.getCollection(RECEIPTS).find(session, eq("_id", id)).first();
        if (identity == null || row == null) { return Receipt.absent(UNAVAILABLE); }
        if (identity.getInteger("schemaVersion", 0) != 1 || row.getInteger("schemaVersion", 0) != 1) { return Receipt.absent(UNAVAILABLE); }
        if ("CONFLICT".equals(identity.getString("state"))) { return Receipt.absent(CONFLICT); }
        if (!"ADMITTED".equals(identity.getString("state"))) { return Receipt.absent(REVOKED); }
        Instant admitted = row.getDate("admittedAt").toInstant();
        Instant expiry = row.getDate("expiresAt").toInstant();
        Instant verifiedAt = canonicalTime(row.getString("verifiedAt"));
        if (!expiry.isAfter(clock.instant()) || !expiry.isAfter(admitted)
                || Duration.between(admitted, expiry).compareTo(Duration.ofDays(90)) > 0) { return Receipt.absent(REVOKED); }
        SourceScope scope = new SourceScope(identity.getString("issuer"), identity.getString("institution"), identity.getString("source"),
                identity.getString("sourceEpoch"), row.getString("signingKeyVersion"), identity.getString("sourceEventId"),
                canonicalTime(row.getString("eventTime")), row.getString("purpose"), row.getString("audience"), row.getString("payloadCommitment"));
        String binding = SourceReceiptIdentity.binding(scope, row.getString("keyCommitment"));
        if (!SourceReceiptIdentity.identity(scope).equals(id) || !binding.equals(identity.getString("bindingCommitment"))
                || !binding.equals(row.getString("bindingCommitment")) || !Objects.equals(identity.get("expiresAt"), row.get("expiresAt"))
                || !Objects.equals(identity.get("admittedAt"), row.get("admittedAt"))
                || verifiedAt.toEpochMilli() > admitted.toEpochMilli() || admitted.isAfter(clock.instant())) {
            return Receipt.absent(CONFLICT);
        }
        if (policy.receiptPolicyDenial(scope, row.getString("keyCommitment"), row.getString("policyVersion"), verifiedAt) != null) {
            return Receipt.absent(REVOKED);
        }
        return new Receipt(VERIFIED, id, scope, row.getString("keyCommitment"), row.getString("policyVersion"), expiry);
    }

    private boolean validLink(Document link, String eventId, Instant createdAt, String commitment) {
        return link.getInteger("schemaVersion", 0) == 1 && "BOUND".equals(link.getString("state"))
                && eventId.equals(link.getString("eventId")) && createdAt.toString().equals(link.getString("eventCreatedAt"))
                && commitment.equals(link.getString("eventCommitment")) && link.getDate("expiresAt").toInstant().isAfter(clock.instant());
    }
    private static boolean sameInterpretation(TransactionRawEvent a, TransactionEnrichedEvent b) {
        return Objects.equals(a.transactionId(), b.transactionId()) && Objects.equals(a.correlationId(), b.correlationId())
                && Objects.equals(a.customerId(), b.customerId()) && Objects.equals(a.accountId(), b.accountId())
                && Objects.equals(a.transactionTimestamp(), b.transactionTimestamp()) && Objects.equals(a.transactionAmount(), b.transactionAmount())
                && Objects.equals(a.merchantInfo(), b.merchantInfo()) && Objects.equals(a.deviceInfo(), b.deviceInfo())
                && Objects.equals(a.locationInfo(), b.locationInfo()) && Objects.equals(a.customerContext(), b.customerContext());
    }
    private SourceLineage transaction(Function<ClientSession, SourceLineage> work) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TRANSACTION);
                try {
                    SourceLineage result = work.apply(session);
                    for (int commitAttempt = 0; ; commitAttempt++) {
                        try { session.commitTransaction(); return result; }
                        catch (MongoException failure) {
                            if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) || commitAttempt == 2) { throw failure; }
                        }
                    }
                } catch (RuntimeException failure) {
                    if (failure instanceof MongoException mongo && mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                        return absent(UNAVAILABLE);
                    }
                    try { if (session.hasActiveTransaction()) { session.abortTransaction(); } } catch (RuntimeException ignored) { }
                    if (failure instanceof MongoException mongo && (mongo.getCode() == 11000
                            || mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) && attempt < 2) { continue; }
                    return absent(UNAVAILABLE);
                }
            } catch (RuntimeException unavailable) { return absent(UNAVAILABLE); }
        }
        return absent(UNAVAILABLE);
    }
    private static Instant canonicalTime(String value) {
        Instant time = Instant.parse(value);
        if (!time.toString().equals(value)) { throw invalid("TIME_INVALID"); }
        return time;
    }
    private static SourceLineage absent(SourceLineage.State state) { return SourceLineage.unavailable(state); }
    private static IllegalStateException invalid(String code) { return new IllegalStateException("SOURCE_LINEAGE_" + code); }
    private record Receipt(SourceLineage.State state, String id, SourceScope scope, String keyCommitment,
                           String policyVersion, Instant expiresAt) {
        static Receipt absent(SourceLineage.State state) { return new Receipt(state, null, null, null, null, null); }
    }
}
