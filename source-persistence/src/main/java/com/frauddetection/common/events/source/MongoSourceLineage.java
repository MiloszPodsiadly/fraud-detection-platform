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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

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
    private static final String BUSINESS_IDENTITIES = SourceEnrichedBusinessIdentitySchema.COLLECTION;
    private static final String TERMINAL_DISPOSITIONS = SourceRawTerminalDispositionSchema.COLLECTION;
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.MAJORITY).readPreference(ReadPreference.primary()).build();
    private static final Set<String> FEATURES = Set.of(FraudFeatureContract.RECENT_TRANSACTION_COUNT,
            FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW,
            FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN,
            FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE,
            FraudFeatureContract.MERCHANT_FREQUENCY_7D, FraudFeatureContract.DEVICE_NOVELTY, FraudFeatureContract.COUNTRY_MISMATCH,
            FraudFeatureContract.PROXY_OR_VPN_DETECTED, FraudFeatureContract.CUSTOMER_SEGMENT,
            FraudFeatureContract.MERCHANT_CATEGORY, FraudFeatureContract.CURRENCY);
    private static final int MAX_ENRICHED_PUBLICATION_BATCH = 50;
    public static final int MAX_ENRICHED_BINDING_BATCH = 1_000;
    private static final Pattern COMMITMENT_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Duration ENRICHED_PUBLICATION_LEASE = Duration.ofSeconds(30);
    private final MongoClient client;
    private final MongoDatabase database;
    private final SourceSignatureVerifier policy;
    private final Stage stage;
    private final Clock clock;
    private final SourceEnrichedPublicationCipher enrichedPublicationCipher;
    private boolean occurrenceWrites;

    public MongoSourceLineage(MongoClient client, String database, String expectedUser, Stage stage,
                              SourceSignatureVerifier policy, Clock clock) {
        this(client, database, expectedUser, stage, policy, clock, null);
    }

    public MongoSourceLineage(MongoClient client, String database, String expectedUser, Stage stage,
                              SourceSignatureVerifier policy, Clock clock,
                              SourceEnrichedPublicationCipher enrichedPublicationCipher) {
        this.client = Objects.requireNonNull(client);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.stage = Objects.requireNonNull(stage);
        this.enrichedPublicationCipher = enrichedPublicationCipher;
        if (database == null || !database.matches("[a-z][a-z0-9_]{0,63}")) { throw invalid("DATABASE_INVALID"); }
        this.database = client.getDatabase(database).withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY).withReadPreference(ReadPreference.primary());
        Long timeout = this.database.getTimeout(TimeUnit.MILLISECONDS);
        if (timeout == null || timeout <= 0 || timeout > 5_000) { throw invalid("OPERATION_TIMEOUT_REQUIRED"); }
        requireServicePrincipal(expectedUser);
        SourceLineageSchema.initialize(this.database, stage == Stage.INGEST);
        if (stage == Stage.INGEST) {
            SourceFeatureOrderGateSchema.initialize(this.database, true, clock);
            SourceRawTerminalDispositionSchema.initialize(this.database, true, clock);
        }
        if (stage == Stage.ENRICHER && enrichedPublicationCipher != null) {
            SourceLineageSchema.initializeEnrichedPublications(this.database, false);
            SourceEnrichedBusinessIdentitySchema.initialize(this.database, false);
            SourceFeatureOrderGateSchema.initialize(this.database, false, clock);
            SourceRawTerminalDispositionSchema.initialize(this.database, false, clock);
        }
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
                    ENRICHED_PUBLICATIONS, BUSINESS_IDENTITIES, SourceFeatureOrderGateSchema.COLLECTION, TERMINAL_DISPOSITIONS,
                    SourceOccurrenceSchema.OCCURRENCES, SourceOccurrenceSchema.COUNTS).contains(collection)) {
                throw invalid("SERVICE_PRIVILEGES_TOO_BROAD");
            }
            for (String action : actions) {
                if (BUSINESS_IDENTITIES.equals(collection) && stage != Stage.ENRICHER) {
                    throw invalid("SERVICE_READ_NOT_AUTHORIZED");
                }
                if (TERMINAL_DISPOSITIONS.equals(collection) && stage != Stage.ENRICHER) {
                    throw invalid("SERVICE_READ_NOT_AUTHORIZED");
                }
                if (action.equals("find")) { read.add(collection); }
                if (action.equals("insert")) { insert.add(collection); }
                if (action.equals("update")) { update.add(collection); }
                boolean permittedWrite = stage == Stage.ENRICHER && Set.of(ENRICHED, ENRICHED_COUNTS,
                        ENRICHED_PUBLICATIONS, SourceFeatureOrderGateSchema.COLLECTION).contains(collection)
                        || stage == Stage.SCORER && Set.of(SourceOccurrenceSchema.OCCURRENCES, SourceOccurrenceSchema.COUNTS).contains(collection);
                boolean permittedFenceInsert = stage == Stage.ENRICHER && BUSINESS_IDENTITIES.equals(collection)
                        && action.equals("insert");
                boolean permittedTerminalInsert = stage == Stage.ENRICHER && TERMINAL_DISPOSITIONS.equals(collection)
                        && action.equals("insert");
                if ((action.equals("insert") || action.equals("update")) && !permittedWrite
                        && !permittedFenceInsert && !permittedTerminalInsert) {
                    throw invalid("SERVICE_WRITE_NOT_AUTHORIZED");
                }
            }
        }
        if (!read.containsAll(Set.of(IDENTITIES, RECEIPTS, RAW, ENRICHED))) { throw invalid("SERVICE_READ_NOT_AUTHORIZED"); }
        if (stage == Stage.ENRICHER && (!read.contains(ENRICHED_COUNTS)
                || !insert.containsAll(Set.of(ENRICHED, ENRICHED_COUNTS)) || !update.containsAll(Set.of(ENRICHED, ENRICHED_COUNTS)))) {
            throw invalid("SERVICE_WRITE_NOT_AUTHORIZED");
        }
        if (stage == Stage.ENRICHER && enrichedPublicationCipher != null
                && (!read.contains(ENRICHED_PUBLICATIONS) || !insert.contains(ENRICHED_PUBLICATIONS)
                || !update.contains(ENRICHED_PUBLICATIONS) || !read.contains(BUSINESS_IDENTITIES)
                || !insert.contains(BUSINESS_IDENTITIES) || update.contains(BUSINESS_IDENTITIES)
                || !read.contains(SourceFeatureOrderGateSchema.COLLECTION)
                || !update.contains(SourceFeatureOrderGateSchema.COLLECTION)
                || insert.contains(SourceFeatureOrderGateSchema.COLLECTION)
                || !read.contains(TERMINAL_DISPOSITIONS) || !insert.contains(TERMINAL_DISPOSITIONS)
                || update.contains(TERMINAL_DISPOSITIONS))) {
            throw invalid("SERVICE_WRITE_NOT_AUTHORIZED");
        }
        occurrenceWrites = stage == Stage.SCORER && read.containsAll(SourceOccurrenceSchema.COLLECTIONS)
                && insert.containsAll(SourceOccurrenceSchema.COLLECTIONS) && update.containsAll(SourceOccurrenceSchema.COLLECTIONS);
    }

    void requireOccurrenceWriter() {
        if (!occurrenceWrites) { throw invalid("OCCURRENCE_WRITE_NOT_AUTHORIZED"); }
    }

    /** Removes encrypted replay material in the source-owner receipt tombstone transaction. */
    public boolean deleteEnrichedPublicationsWithinTransaction(ClientSession session, String receiptId) {
        if (stage != Stage.INGEST || session == null || !session.hasActiveTransaction() || receiptId == null
                || !receiptId.matches("^[0-9a-f]{64}$")) { return false; }
        try {
            boolean publicationsDeleted = database.getCollection(ENRICHED_PUBLICATIONS)
                    .deleteMany(session, eq("receiptId", receiptId)).wasAcknowledged();
            boolean identitiesDeleted = database.getCollection(BUSINESS_IDENTITIES)
                    .deleteMany(session, eq("receiptId", receiptId)).wasAcknowledged();
            return publicationsDeleted && identitiesDeleted;
        } catch (RuntimeException unavailable) { return false; }
    }

    /** Same authenticated durable chain validation as live processing, used only for exact private recovery. */
    SourceLineage recoverEnriched(String receiptId, String commitment) {
        return transaction(session -> recoverEnriched(session, receiptId, commitment));
    }

    SourceLineage recoverEnriched(ClientSession session, String receiptId, String commitment) {
        if (session == null || !session.hasActiveTransaction()) { return absent(UNAVAILABLE); }
        Document link = database.getCollection(ENRICHED).find(session, eq("eventCommitment", commitment)).first();
        if (link == null || !receiptId.equals(link.getString("receiptId"))) { return absent(UNAVAILABLE); }
        return enrichedLink(session, link, commitment);
    }

    Instant receiptExpiry(String receiptId) {
        return database.getCollection(RECEIPTS).find(eq("_id", receiptId)).first().getDate("expiresAt").toInstant();
    }

    public SourceLineage bindRaw(SourceSignatureVerifier.Verified evidence, String receiptId, TransactionRawEvent event) {
        if (stage != Stage.INGEST || evidence == null || policy.currentDenial(evidence) != null
                || !SourceReceiptIdentity.identity(evidence.scope()).equals(receiptId)) { return absent(REVOKED); }
        return transaction(session -> bindRawWithinTransaction(session, evidence, receiptId, event));
    }

    /**
     * Joins the receipt owner's transaction so admission, this raw link and its publication intent can
     * commit atomically. The caller must use the same MongoClient and database as this lineage owner.
     */
    public SourceLineage bindRawWithinTransaction(ClientSession session, SourceSignatureVerifier.Verified evidence,
                                                   String receiptId, TransactionRawEvent event) {
        if (session == null || !session.hasActiveTransaction()) { return absent(UNAVAILABLE); }
        if (stage != Stage.INGEST || evidence == null || event == null
                || policy.currentDenial(evidence) != null
                || !SourceReceiptIdentity.identity(evidence.scope()).equals(receiptId)) { return absent(REVOKED); }
        Receipt receipt = receipt(session, receiptId);
        if (receipt.state != VERIFIED) { return absent(receipt.state); }
        if (!evidence.scope().equals(receipt.scope) || !evidence.keyCommitment().equals(receipt.keyCommitment)
                || !evidence.policyVersion().equals(receipt.policyVersion)) { return absent(REVOKED); }
        SourceLineage bound = bind(session, RAW, RAW_COUNTS, event.eventId(), event.createdAt(),
                SourceEventCommitment.raw(event), receipt, SourceReceiptIdentity.binding(receipt.scope, receipt.keyCommitment));
        if (bound.state() != VERIFIED) { return bound; }
        return acquireFeatureOrderGate(session, receipt.scope, event.customerId(), receipt.id, bound.eventCommitment())
                ? bound : SourceLineage.unavailable(ORDER_PENDING);
    }

    private boolean acquireFeatureOrderGate(ClientSession session, SourceScope scope, String customerId,
                                            String receiptId, String rawCommitment) {
        MongoCollection<Document> gates = database.getCollection(SourceFeatureOrderGateSchema.COLLECTION);
        String id = SourceFeatureOrderIdentity.identity(scope, customerId);
        Date now = Date.from(clock.instant());
        Document existing = gates.find(session, eq("_id", id)).first();
        if (existing == null) {
            gates.insertOne(session, new Document("_id", id).append("schemaVersion", 1).append("status", "ACTIVE")
                    .append("receiptId", receiptId).append("rawCommitment", rawCommitment).append("activatedAt", now)
                    .append("resolvedAt", null).append("resolution", null));
            return true;
        }
        if (SourceFeatureOrderGateSchema.isActive(existing, receiptId, rawCommitment)) { return true; }
        if (SourceFeatureOrderGateSchema.isResolvedFor(existing, receiptId, rawCommitment)) { return true; }
        if (!SourceFeatureOrderGateSchema.isResolvedFor(existing, existing.getString("receiptId"),
                existing.getString("rawCommitment"))) { return false; }
        var activated = gates.updateOne(session, and(eq("_id", id), eq("status", existing.getString("status")),
                        eq("receiptId", existing.getString("receiptId")), eq("rawCommitment", existing.getString("rawCommitment")),
                        eq("resolvedAt", existing.getDate("resolvedAt")), eq("resolution", existing.getString("resolution"))),
                combine(set("status", "ACTIVE"), set("receiptId", receiptId), set("rawCommitment", rawCommitment),
                        set("activatedAt", now), set("resolvedAt", null), set("resolution", null)));
        return activated.wasAcknowledged() && activated.getMatchedCount() == 1 && activated.getModifiedCount() == 1;
    }

    private boolean activeFeatureOrderGate(ClientSession session, SourceLineage authority, String customerId,
                                           String rawCommitment) {
        if (session == null || authority == null || authority.state() != VERIFIED || authority.source() == null) { return false; }
        String id = SourceFeatureOrderIdentity.identity(authority.source(), customerId);
        Document gate = database.getCollection(SourceFeatureOrderGateSchema.COLLECTION).find(session, eq("_id", id)).first();
        return SourceFeatureOrderGateSchema.isActive(gate, authority.receiptId(), rawCommitment);
    }

    @Override public SourceLineage rejectLateRawObservation(TransactionRawEvent input) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || input == null) { return absent(UNAVAILABLE); }
        return transaction(session -> {
            SourceLineage parent = raw(session, input);
            if (parent.state() != VERIFIED) { return parent; }
            String publicationId = enrichedPublicationId(parent.eventCommitment());
            if (database.getCollection(ENRICHED_PUBLICATIONS).find(session, eq("_id", publicationId)).first() != null) {
                return absent(CONFLICT);
            }
            MongoCollection<Document> dispositions = database.getCollection(TERMINAL_DISPOSITIONS);
            String dispositionId = SourceRawTerminalDispositionSchema.identity(parent.receiptId());
            Document existingDisposition = dispositions.find(session, eq("_id", dispositionId)).first();
            if (existingDisposition != null) {
                return SourceRawTerminalDispositionSchema.isExactDisposition(existingDisposition, parent.receiptId(),
                        parent.eventCommitment()) ? parent : absent(CONFLICT);
            }
            String gateId = SourceFeatureOrderIdentity.identity(parent.source(), input.customerId());
            MongoCollection<Document> gates = database.getCollection(SourceFeatureOrderGateSchema.COLLECTION);
            Document gate = gates.find(session, eq("_id", gateId)).first();
            if (SourceFeatureOrderGateSchema.isResolvedFor(gate, parent.receiptId(), parent.eventCommitment())
                    && "LATE_REJECTED".equals(gate.getString("status"))) { return absent(UNAVAILABLE); }
            if (!SourceFeatureOrderGateSchema.isActive(gate, parent.receiptId(), parent.eventCommitment())) {
                return absent(UNAVAILABLE);
            }
            Date decidedAt = Date.from(clock.instant());
            var resolved = gates.updateOne(session, and(eq("_id", gateId), eq("status", "ACTIVE"),
                            eq("receiptId", parent.receiptId()), eq("rawCommitment", parent.eventCommitment())),
                    combine(set("status", "LATE_REJECTED"), set("resolvedAt", decidedAt),
                            set("resolution", "LATE_REJECTED")));
            if (!resolved.wasAcknowledged() || resolved.getMatchedCount() != 1) { return absent(UNAVAILABLE); }
            dispositions.insertOne(session, SourceRawTerminalDispositionSchema.disposition(parent.receiptId(),
                    parent.eventCommitment(), decidedAt));
            return parent;
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
        if (enrichedPublicationCipher != null) {
            SourceEnrichedPublication publication = prepareEnrichedPublication(input, output);
            if (publication.authority().state() != VERIFIED) { return publication.authority(); }
            return SourceEventCommitment.enriched(output).equals(SourceEventCommitment.enriched(publication.event()))
                    ? publication.authority() : absent(CONFLICT);
        }
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

    @Override public SourceEnrichedPublication prepareEnrichedPublication(TransactionRawEvent input,
                                                                            TransactionEnrichedEvent candidate) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null) {
            return SourceEnrichedPublication.unavailable(UNAVAILABLE);
        }
        return transaction(session -> {
            SourceLineage parent = raw(session, input);
            if (parent.state() != VERIFIED) { return SourceEnrichedPublication.unavailable(parent.state()); }
            if (!sameInterpretation(input, candidate) || candidate.featureSnapshot() == null
                    || !FEATURES.equals(candidate.featureSnapshot().keySet())) {
                return SourceEnrichedPublication.unavailable(CONFLICT);
            }
            Receipt receipt = receipt(session, parent.receiptId());
            if (receipt.state != VERIFIED) { return SourceEnrichedPublication.unavailable(receipt.state); }
            String publicationId = enrichedPublicationId(parent.eventCommitment());
            MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
            Document existing = publications.find(session, eq("_id", publicationId)).first();
            if (existing != null) {
                SourceEnrichedPublication stored = readEnrichedPublication(session, input, parent, existing);
                return stored.authority().state() == VERIFIED && !stored.historyApplied()
                        && !activeFeatureOrderGate(session, stored.authority(), input.customerId(), parent.eventCommitment())
                        ? SourceEnrichedPublication.unavailable(UNAVAILABLE) : stored;
            }
            if (!activeFeatureOrderGate(session, parent, input.customerId(), parent.eventCommitment())) {
                return SourceEnrichedPublication.unavailable(UNAVAILABLE);
            }

            String commitment = SourceEventCommitment.enriched(candidate);
            String businessIdentity = SourceEventCommitment.businessTransaction(receipt.scope, candidate.customerId(),
                    candidate.transactionId());
            Document fence = database.getCollection(BUSINESS_IDENTITIES).find(session, eq("_id", businessIdentity)).first();
            if (fence != null) {
                boolean exactFence = fence.getInteger("schemaVersion", 0) == 1 && "FENCE".equals(fence.getString("recordType"))
                        && receipt.id.equals(fence.getString("receiptId"))
                        && parent.eventCommitment().equals(fence.getString("rawCommitment"))
                        && commitment.equals(fence.getString("enrichedCommitment"));
                return SourceEnrichedPublication.unavailable(exactFence ? UNAVAILABLE : CONFLICT);
            }

            Document linked = database.getCollection(ENRICHED).find(session,
                    eq("parentCommitment", parent.eventCommitment())).first();
            if (linked != null) { return SourceEnrichedPublication.unavailable(UNAVAILABLE); }

            SourceLineage bound = bind(session, ENRICHED, ENRICHED_COUNTS, candidate.eventId(), candidate.createdAt(),
                    commitment, receipt, parent.eventCommitment());
            if (bound.state() != VERIFIED) { return SourceEnrichedPublication.unavailable(bound.state()); }
            database.getCollection(BUSINESS_IDENTITIES).insertOne(session, SourceEnrichedBusinessIdentitySchema.newFence(
                    businessIdentity, receipt.id, parent.eventCommitment(), commitment, Date.from(receipt.expiresAt)));
            Document row = new Document("_id", publicationId).append("schemaVersion", 1)
                    .append("receiptId", receipt.id).append("rawCommitment", parent.eventCommitment())
                    .append("enrichedCommitment", commitment).append("eventId", candidate.eventId())
                    .append("eventCreatedAt", candidate.createdAt().toString()).append("expiresAt", Date.from(receipt.expiresAt))
                    .append("state", "PENDING").append("publishedAt", null)
                    .append("historyState", "PENDING").append("historyAppliedAt", null)
                    .append("historyAttempts", 0).append("historyNextAttemptAt", Date.from(clock.instant()))
                    .append("preparedAt", Date.from(clock.instant())).append("attempts", 0)
                    .append("nextAttemptAt", Date.from(clock.instant())).append("recoveryEpoch", 0);
            enrichedPublicationCipher.encrypt(row, candidate);
            publications.insertOne(session, row);
            return new SourceEnrichedPublication(bound, candidate, false, false);
        }, SourceEnrichedPublication.unavailable(UNAVAILABLE));
    }

    @Override public SourceEnrichedPublicationLookup findEnrichedPublication(TransactionRawEvent input) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || input == null) {
            return SourceEnrichedPublicationLookup.unavailable(UNAVAILABLE);
        }
        return transaction(session -> {
            SourceLineage parent = raw(session, input);
            if (parent.state() != VERIFIED) { return SourceEnrichedPublicationLookup.unavailable(parent.state()); }
            String dispositionId = SourceRawTerminalDispositionSchema.identity(parent.receiptId());
            Document disposition = database.getCollection(TERMINAL_DISPOSITIONS)
                    .find(session, eq("_id", dispositionId)).first();
            if (disposition != null) {
                if (!SourceRawTerminalDispositionSchema.isExactDisposition(disposition, parent.receiptId(),
                        parent.eventCommitment())) { return SourceEnrichedPublicationLookup.unavailable(CONFLICT); }
                String terminalPublicationId = enrichedPublicationId(parent.eventCommitment());
                if (database.getCollection(ENRICHED_PUBLICATIONS).find(session, eq("_id", terminalPublicationId)).first() != null) {
                    return SourceEnrichedPublicationLookup.unavailable(CONFLICT);
                }
                return SourceEnrichedPublicationLookup.lateRejected(parent);
            }
            String publicationId = enrichedPublicationId(parent.eventCommitment());
            Document row = database.getCollection(ENRICHED_PUBLICATIONS).find(session, eq("_id", publicationId)).first();
            if (row == null) {
                if (activeFeatureOrderGate(session, parent, input.customerId(), parent.eventCommitment())) {
                    return SourceEnrichedPublicationLookup.notFound(parent);
                }
                return SourceEnrichedPublicationLookup.unavailable(UNAVAILABLE);
            }
            SourceEnrichedPublication publication = readEnrichedPublication(session, input, parent, row);
            boolean orderAllowed = publication.authority().state() == VERIFIED && (publication.historyApplied()
                    || activeFeatureOrderGate(session, publication.authority(), input.customerId(), parent.eventCommitment()));
            return publication.authority().state() == VERIFIED && publication.event() != null && orderAllowed
                    ? SourceEnrichedPublicationLookup.found(publication)
                    : SourceEnrichedPublicationLookup.unavailable(publication.authority().state());
        }, SourceEnrichedPublicationLookup.unavailable(UNAVAILABLE));
    }

    @Override public SourceEnrichedPublicationClaim claimEnrichedPublication(TransactionRawEvent input,
                                                                               TransactionEnrichedEvent output) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || input == null || output == null) {
            return SourceEnrichedPublicationClaim.unavailable(UNAVAILABLE);
        }
        return transaction(session -> {
            SourceLineage parent = raw(session, input);
            if (parent.state() != VERIFIED) { return claimUnavailable(parent.state()); }
            String publicationId = enrichedPublicationId(parent.eventCommitment());
            Document row = database.getCollection(ENRICHED_PUBLICATIONS).find(session, eq("_id", publicationId)).first();
            if (row == null) { return claimUnavailable(UNVERIFIED); }
            SourceEnrichedPublication publication = readEnrichedPublication(session, input, parent, row);
            if (publication.authority().state() != VERIFIED) { return claimUnavailable(publication.authority().state()); }
            if (!SourceEventCommitment.enriched(output).equals(SourceEventCommitment.enriched(publication.event()))) {
                return claimUnavailable(CONFLICT);
            }
            if (!publication.historyApplied()
                    && !activeFeatureOrderGate(session, publication.authority(), input.customerId(), parent.eventCommitment())) {
                return claimUnavailable(UNAVAILABLE);
            }
            return claimPreparedRow(session, row, publication);
        }, SourceEnrichedPublicationClaim.unavailable(UNAVAILABLE));
    }

    @Override public List<SourceEnrichedPublicationClaim> claimNextEnrichedPublications(int limit) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || limit < 1 || limit > MAX_ENRICHED_PUBLICATION_BATCH) {
            return List.of();
        }
        // A caller must finish one lease before asking for the next item; never preclaim a batch.
        SourceEnrichedPublicationClaim claim = claimNextEnrichedPublication();
        return claim.status() == SourceEnrichedPublicationClaim.Status.CLAIMED ? List.of(claim) : List.of();
    }

    @Override public SourceLineage markEnrichedHistoryApplied(SourceEnrichedPublicationClaim claim) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || claim == null
                || claim.status() != SourceEnrichedPublicationClaim.Status.CLAIMED || claim.event() == null) {
            return absent(UNAVAILABLE);
        }
        SourceLineage result = transaction(session -> {
            MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
            Document row = publications.find(session, eq("_id", claim.publicationId())).first();
            if (!validClaimIdentity(row, claim)) { return absent(UNAVAILABLE); }
            SourceEnrichedPublication stored = readStoredEnrichedPublication(session, row);
            if (stored.authority().state() != VERIFIED || stored.event() == null) { return stored.authority(); }
            if ("APPLIED".equals(row.getString("historyState"))) { return stored.authority(); }
            Instant now = clock.instant();
            String leaseField = claim.published() ? "historyLeaseToken" : "leaseToken";
            String untilField = claim.published() ? "historyLeaseUntil" : "leaseUntil";
            String leaseState = claim.published() ? "PUBLISHED" : "PUBLISHING";
            String gateId = SourceFeatureOrderIdentity.identity(stored.authority().source(), stored.event().customerId());
            MongoCollection<Document> gates = database.getCollection(SourceFeatureOrderGateSchema.COLLECTION);
            Document gate = gates.find(session, eq("_id", gateId)).first();
            if (!SourceFeatureOrderGateSchema.isActive(gate, stored.authority().receiptId(), row.getString("rawCommitment"))) {
                return absent(UNAVAILABLE);
            }
            var resolved = gates.updateOne(session, and(eq("_id", gateId), eq("status", "ACTIVE"),
                            eq("receiptId", stored.authority().receiptId()), eq("rawCommitment", row.getString("rawCommitment"))),
                    combine(set("status", "RESOLVED"), set("resolvedAt", Date.from(now)), set("resolution", "HISTORY_APPLIED")));
            if (!resolved.wasAcknowledged() || resolved.getMatchedCount() != 1 || resolved.getModifiedCount() != 1) {
                throw invalid("FEATURE_ORDER_GATE_RESOLUTION_UNRESOLVED");
            }
            var updated = publications.updateOne(session, and(eq("_id", claim.publicationId()), eq("state", leaseState),
                            eq("historyState", "PENDING"), eq(leaseField, claim.claimToken()), gt(untilField, Date.from(now))),
                    combine(set("historyState", "APPLIED"), set("historyAppliedAt", Date.from(now)),
                            unset("historyLeaseToken"), unset("historyLeaseUntil"), unset("historyFailureCode")));
            if (updated.wasAcknowledged() && updated.getMatchedCount() == 1) { return stored.authority(); }
            throw invalid("FEATURE_LINEAGE_HISTORY_ACK_UNRESOLVED");
        });
        if (result.state() == VERIFIED) { return result; }
        try {
            Document row = database.getCollection(ENRICHED_PUBLICATIONS).find(eq("_id", claim.publicationId())).first();
            if (row != null && "APPLIED".equals(row.getString("historyState"))
                    && claim.authority().receiptId().equals(row.getString("receiptId"))
                    && SourceEventCommitment.enriched(claim.event()).equals(row.getString("enrichedCommitment"))) {
                return transaction(session -> readStoredEnrichedPublication(session,
                        database.getCollection(ENRICHED_PUBLICATIONS).find(session, eq("_id", claim.publicationId())).first()).authority());
            }
        } catch (RuntimeException unavailable) {
            // The next fenced retry resolves an unknown Mongo acknowledgement.
        }
        return absent(UNAVAILABLE);
    }

    @Override public void recordEnrichedHistoryFailure(SourceEnrichedPublicationClaim claim) {
        recordEnrichedHistoryFailure(claim, false);
    }

    @Override public void recordEnrichedHistoryFailure(SourceEnrichedPublicationClaim claim, boolean permanent) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || claim == null
                || claim.status() != SourceEnrichedPublicationClaim.Status.CLAIMED) { return; }
        Instant now = clock.instant();
        try {
            MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
            String expectedHistoryState = claim.historyApplied() ? "APPLIED" : "PENDING";
            Document row = publications.find(and(eq("_id", claim.publicationId()),
                    eq("receiptId", claim.authority().receiptId()),
                    eq("enrichedCommitment", SourceEventCommitment.enriched(claim.event())),
                    eq("historyState", expectedHistoryState), gt("expiresAt", Date.from(now)),
                    eq(claim.published() ? "historyLeaseToken" : "leaseToken", claim.claimToken()))).first();
            if (row == null) { return; }
            int nextAttempt = Math.min(MAX_ENRICHED_HISTORY_ATTEMPTS, historyAttempts(row) + 1);
            boolean terminal = permanent || nextAttempt >= MAX_ENRICHED_HISTORY_ATTEMPTS;
            String failure = permanent ? "HISTORY_DATA_INVALID" : "HISTORY_RETRY_EXHAUSTED";
            Date retryAt = Date.from(now.plusSeconds(historyRetryDelaySeconds(nextAttempt)));
            var updates = terminal
                    ? combine(set("historyState", "QUARANTINED"), set("historyAttempts", nextAttempt),
                            set("historyFailureCode", failure), unset("historyLeaseToken"), unset("historyLeaseUntil"))
                    : combine(set("historyAttempts", nextAttempt), set("historyNextAttemptAt", retryAt),
                            unset("historyFailureCode"));
            if (claim.published()) {
                var leaseFilter = and(eq("_id", claim.publicationId()),
                                eq("state", "PUBLISHED"), eq("historyState", "PENDING"),
                                eq("receiptId", claim.authority().receiptId()),
                                eq("enrichedCommitment", SourceEventCommitment.enriched(claim.event())),
                                gt("expiresAt", Date.from(now)), eq("historyLeaseToken", claim.claimToken()));
                var publishedUpdates = terminal ? updates
                        : combine(updates, unset("historyLeaseToken"), unset("historyLeaseUntil"));
                publications.updateOne(leaseFilter, publishedUpdates);
            } else {
                var leaseFilter = and(eq("_id", claim.publicationId()),
                                eq("state", "PUBLISHING"), eq("historyState", expectedHistoryState),
                                eq("receiptId", claim.authority().receiptId()),
                                eq("enrichedCommitment", SourceEventCommitment.enriched(claim.event())),
                                gt("expiresAt", Date.from(now)), eq("leaseToken", claim.claimToken()));
                var pendingUpdates = terminal
                        ? combine(updates, set("state", "PENDING"), inc("attempts", -1),
                                unset("leaseToken"), unset("leaseUntil"), unset("nextAttemptAt"))
                        : combine(updates, set("state", "PENDING"), set("nextAttemptAt", retryAt), inc("attempts", -1),
                                unset("leaseToken"), unset("leaseUntil"));
                publications.updateOne(leaseFilter, pendingUpdates);
            }
        } catch (RuntimeException unavailable) {
            // Lease expiry remains the retry path; Redis markers reconcile an unknown history acknowledgement.
        }
    }

    @Override public boolean renewEnrichedPublicationLeaseForSend(SourceEnrichedPublicationClaim claim,
                                                                   long minimumRemainingMillis) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || claim == null
                || claim.status() != SourceEnrichedPublicationClaim.Status.CLAIMED || claim.published()
                || minimumRemainingMillis <= 0 || minimumRemainingMillis >= ENRICHED_PUBLICATION_LEASE.toMillis()) {
            return false;
        }
        return transaction(session -> {
            Instant now = clock.instant();
            MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
            Document row = publications.find(session, eq("_id", claim.publicationId())).first();
            if (!validClaimIdentity(row, claim) || !"PUBLISHING".equals(row.getString("state"))
                    || !"APPLIED".equals(row.getString("historyState"))
                    || !(row.get("leaseUntil") instanceof Date until) || !until.toInstant().isAfter(now)) {
                return false;
            }
            SourceEnrichedPublication stored = readStoredEnrichedPublication(session, row);
            if (stored.authority().state() != VERIFIED || stored.event() == null
                    || !SourceEventCommitment.enriched(claim.event()).equals(SourceEventCommitment.enriched(stored.event()))) {
                return false;
            }
            Date renewedUntil = Date.from(now.plus(ENRICHED_PUBLICATION_LEASE));
            var renewed = publications.updateOne(session, and(eq("_id", claim.publicationId()),
                            eq("state", "PUBLISHING"), eq("historyState", "APPLIED"),
                            eq("leaseToken", claim.claimToken()), eq("receiptId", claim.authority().receiptId()),
                            eq("enrichedCommitment", SourceEventCommitment.enriched(claim.event())),
                            gt("leaseUntil", Date.from(now)), gt("expiresAt", Date.from(now))),
                    set("leaseUntil", renewedUntil));
            return renewed.wasAcknowledged() && renewed.getMatchedCount() == 1;
        }, false);
    }

    @Override public void deferEnrichedPublication(SourceEnrichedPublicationClaim claim) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || claim == null
                || claim.status() != SourceEnrichedPublicationClaim.Status.CLAIMED || claim.published()) { return; }
        Instant now = clock.instant();
        try {
            database.getCollection(ENRICHED_PUBLICATIONS).updateOne(and(eq("_id", claim.publicationId()),
                            eq("state", "PUBLISHING"), eq("historyState", "APPLIED"),
                            eq("leaseToken", claim.claimToken()), eq("enrichedCommitment", SourceEventCommitment.enriched(claim.event())),
                            gt("attempts", 0)),
                    combine(set("state", "PENDING"), set("nextAttemptAt", Date.from(now.plusSeconds(30))),
                            inc("attempts", -1), unset("leaseToken"), unset("leaseUntil"), unset("failureCode")));
        } catch (RuntimeException unavailable) {
            // The exact immutable publication remains recoverable after this claim expires.
        }
    }

    @Override public SourceLineage markEnrichedPublished(SourceEnrichedPublicationClaim claim) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || claim == null
                || claim.status() != SourceEnrichedPublicationClaim.Status.CLAIMED || claim.published()) { return absent(UNAVAILABLE); }
        SourceLineage result = transaction(session -> {
            MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
            Document row = publications.find(session, eq("_id", claim.publicationId())).first();
            if (row == null || !claim.claimToken().equals(row.getString("leaseToken"))
                    || !"PUBLISHING".equals(row.getString("state"))
                    || !"APPLIED".equals(row.getString("historyState"))
                    || !(row.get("leaseUntil") instanceof Date leaseUntil) || !leaseUntil.toInstant().isAfter(clock.instant())
                    || !claim.event().eventId().equals(row.getString("eventId"))
                    || !claim.event().createdAt().toString().equals(row.getString("eventCreatedAt"))
                    || !SourceEventCommitment.enriched(claim.event()).equals(row.getString("enrichedCommitment"))) {
                if (row != null && "PUBLISHED".equals(row.getString("state"))
                        && SourceEventCommitment.enriched(claim.event()).equals(row.getString("enrichedCommitment"))) {
                    return readStoredEnrichedPublication(session, row).authority();
                }
                return absent(UNAVAILABLE);
            }
            SourceEnrichedPublication stored = readStoredEnrichedPublication(session, row);
            if (stored.authority().state() != VERIFIED || stored.event() == null) { return stored.authority(); }
            var updated = publications.updateOne(session, and(eq("_id", claim.publicationId()), eq("state", "PUBLISHING"),
                            eq("leaseToken", claim.claimToken()), eq("historyState", "APPLIED"),
                            gt("leaseUntil", Date.from(clock.instant())),
                            eq("enrichedCommitment", SourceEventCommitment.enriched(claim.event()))),
                    combine(set("state", "PUBLISHED"), set("publishedAt", Date.from(clock.instant())),
                            unset("leaseToken"), unset("leaseUntil"), unset("failureCode")));
            if (!updated.wasAcknowledged() || updated.getMatchedCount() != 1 || updated.getModifiedCount() != 1) {
                return absent(UNAVAILABLE);
            }
            return stored.authority();
        });
        if (result.state() == VERIFIED) { return result; }
        return reconcilePublishedClaim(claim);
    }

    @Override public void recordEnrichedPublishFailure(SourceEnrichedPublicationClaim claim) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || claim == null
                || claim.status() != SourceEnrichedPublicationClaim.Status.CLAIMED) { return; }
        Instant now = clock.instant();
        int attempts = claim.attempts();
        boolean exhausted = attempts >= MAX_ENRICHED_PUBLICATION_ATTEMPTS;
        Date retryAt = Date.from(now.plusSeconds(Math.min(300, 1L << Math.min(attempts, 8))));
        try {
            var update = database.getCollection(ENRICHED_PUBLICATIONS).updateOne(and(eq("_id", claim.publicationId()),
                            eq("state", "PUBLISHING"), eq("leaseToken", claim.claimToken()), gt("leaseUntil", Date.from(now))),
                    exhausted ? combine(set("state", "RETRY_EXHAUSTED"), set("failureCode", "PUBLISH_FAILED"),
                            unset("leaseToken"), unset("leaseUntil"), unset("nextAttemptAt"))
                            : combine(set("state", "PENDING"), set("failureCode", "PUBLISH_FAILED"), set("nextAttemptAt", retryAt),
                                    unset("leaseToken"), unset("leaseUntil")));
            if (!update.wasAcknowledged() || update.getMatchedCount() != 1) {
                // Lease expiry remains the safe recovery path after a lost update acknowledgement.
            }
        } catch (RuntimeException unavailable) {
            // The lease expires and the same encrypted event is claimed again; no new identity is created.
        }
    }

    @Override public SourceLineageAccess.EnrichedPublicationHealth enrichedPublicationHealth() {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null) {
            return new SourceLineageAccess.EnrichedPublicationHealth(0, 0, 0, 0, 0);
        }
        Date now = Date.from(clock.instant());
        MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
        var active = gt("expiresAt", now);
        var waitingToPublish = and(in("historyState", "PENDING", "APPLIED"),
                or(eq("state", "PENDING"), and(eq("state", "PUBLISHING"),
                        or(exists("leaseUntil", false), lte("leaseUntil", now)))));
        var waitingForHistory = and(eq("state", "PUBLISHED"), eq("historyState", "PENDING"),
                or(exists("historyLeaseUntil", false), lte("historyLeaseUntil", now)));
        var leasedForPublish = and(eq("state", "PUBLISHING"), gt("leaseUntil", now));
        var leasedForHistory = and(eq("state", "PUBLISHED"), eq("historyState", "PENDING"),
                gt("historyLeaseUntil", now));
        long pending = publications.countDocuments(and(active, or(waitingToPublish, waitingForHistory)));
        long leased = publications.countDocuments(and(active, or(leasedForPublish, leasedForHistory)));
        long exhausted = publications.countDocuments(and(active, eq("state", "RETRY_EXHAUSTED")));
        long quarantined = publications.countDocuments(and(active, or(eq("state", "QUARANTINED"),
                eq("historyState", "QUARANTINED"))));
        Document oldest = publications.find(and(active, or(waitingToPublish, waitingForHistory)))
                .sort(new Document("preparedAt", 1)).projection(new Document("preparedAt", 1).append("eventCreatedAt", 1)).first();
        long age = oldest == null ? 0 : publicationAge(oldest, clock.instant());
        return new SourceLineageAccess.EnrichedPublicationHealth(pending, leased, exhausted, quarantined, age);
    }

    @Override public SourceLineageAccess.EnrichedPublicationRedriveResult redriveEnrichedPublication(String publicationId,
                                                                                                    String reason) {
        if (stage != Stage.ENRICHER || enrichedPublicationCipher == null || publicationId == null
                || !publicationId.matches("^[0-9a-f]{64}$") || reason == null
                || !Set.of("BROKER_RECOVERED", "OPERATOR_RECONCILIATION").contains(reason)) {
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.INVALID, 0);
        }
        Date now = Date.from(clock.instant());
        MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
        Document row;
        try { row = publications.find(eq("_id", publicationId)).first(); }
        catch (RuntimeException unavailable) { return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.UNAVAILABLE, 0); }
        if (row != null && Set.of("PENDING", "PUBLISHED").contains(row.getString("state"))
                && "QUARANTINED".equals(row.getString("historyState"))) {
            return redriveQuarantinedHistory(publications, row, publicationId, reason, now);
        }
        if (row == null || !"RETRY_EXHAUSTED".equals(row.getString("state"))) {
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.UNAVAILABLE, 0);
        }
        if (!"PUBLISH_FAILED".equals(row.getString("failureCode")) || !(row.get("expiresAt") instanceof Date expiry)
                || !expiry.after(now) || publicationAttempts(row) != MAX_ENRICHED_PUBLICATION_ATTEMPTS) {
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.INVALID, publicationRecoveryEpoch(row));
        }
        int epoch = publicationRecoveryEpoch(row);
        if (epoch < 0) { return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.INVALID, 0); }
        if (epoch >= MAX_ENRICHED_PUBLICATION_RECOVERY_EPOCHS) {
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.EXHAUSTED, epoch);
        }
        SourceEnrichedPublication verified = transaction(session -> {
            Document current = publications.find(session, eq("_id", publicationId)).first();
            if (current == null) { return SourceEnrichedPublication.unavailable(UNAVAILABLE); }
            SourceEnrichedPublication stored = readStoredEnrichedPublication(session, current);
            return stored.authority().state() == VERIFIED && !stored.historyApplied()
                    && !activeFeatureOrderGate(session, stored.authority(), stored.event().customerId(), current.getString("rawCommitment"))
                    ? SourceEnrichedPublication.unavailable(UNAVAILABLE) : stored;
        }, SourceEnrichedPublication.unavailable(UNAVAILABLE));
        if (verified.authority().state() == UNAVAILABLE) {
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.UNAVAILABLE, epoch);
        }
        if (verified.authority().state() != VERIFIED || verified.event() == null) {
            markEnrichedQuarantine(row, verified.authority().state() == CONFLICT ? "PAYLOAD_UNAVAILABLE" : "AUTHORITY_DENIED");
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.AUTHORITY_DENIED, epoch);
        }
        Date redriveAt = Date.from(clock.instant());
        int nextEpoch = epoch + 1;
        var epochFilter = epoch == 0 ? or(eq("recoveryEpoch", 0), exists("recoveryEpoch", false)) : eq("recoveryEpoch", epoch);
        try {
            var update = publications.updateOne(and(eq("_id", publicationId), eq("state", "RETRY_EXHAUSTED"),
                            eq("failureCode", "PUBLISH_FAILED"), eq("enrichedCommitment", row.getString("enrichedCommitment")),
                            eq("eventId", row.getString("eventId")), eq("eventCreatedAt", row.getString("eventCreatedAt")),
                            eq("attempts", MAX_ENRICHED_PUBLICATION_ATTEMPTS), eq("keyId", row.getString("keyId")),
                            eq("nonce", row.get("nonce")), eq("ciphertext", row.get("ciphertext")),
                            epochFilter, gt("expiresAt", redriveAt)),
                    combine(set("state", "PENDING"), set("attempts", 0), set("recoveryEpoch", nextEpoch),
                            set("nextAttemptAt", redriveAt), set("lastRedriveAt", redriveAt), set("redriveReason", reason),
                            unset("failureCode"), unset("leaseToken"), unset("leaseUntil")));
            if (update.wasAcknowledged() && update.getMatchedCount() == 1 && update.getModifiedCount() == 1) {
                return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.REQUEUED, nextEpoch);
            }
        } catch (RuntimeException unresolvedWrite) {
            // Reconcile the exact durable intent below after an unknown write result.
        }
        try {
            Document current = publications.find(eq("_id", publicationId)).first();
            if (current != null && Set.of("PENDING", "PUBLISHING", "PUBLISHED").contains(current.getString("state"))
                    && publicationRecoveryEpoch(current) == nextEpoch && reason.equals(current.getString("redriveReason"))
                    && Objects.equals(redriveAt, current.getDate("lastRedriveAt"))
                    && Objects.equals(row.getString("receiptId"), current.getString("receiptId"))
                    && Objects.equals(row.getString("enrichedCommitment"), current.getString("enrichedCommitment"))
                    && Objects.equals(row.getString("eventId"), current.getString("eventId"))
                    && Objects.equals(row.getString("eventCreatedAt"), current.getString("eventCreatedAt"))
                    && Objects.equals(row.get("expiresAt"), current.get("expiresAt"))
                    && Objects.equals(row.get("keyId"), current.get("keyId")) && Objects.equals(row.get("nonce"), current.get("nonce"))
                    && Objects.equals(row.get("ciphertext"), current.get("ciphertext"))) {
                return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.REQUEUED, nextEpoch);
            }
        } catch (RuntimeException unavailable) {
            // Keep the outcome unresolved if exact state cannot be read back.
        }
        return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.UNAVAILABLE, epoch);
    }

    private SourceLineageAccess.EnrichedPublicationRedriveResult redriveQuarantinedHistory(
            MongoCollection<Document> publications, Document row, String publicationId, String reason, Date now) {
        int epoch = publicationRecoveryEpoch(row);
        if (!"OPERATOR_RECONCILIATION".equals(reason)
                || !("PENDING".equals(row.getString("state")) || "PUBLISHED".equals(row.getString("state")))
                || !Set.of("HISTORY_RETRY_EXHAUSTED", "HISTORY_DATA_INVALID").contains(row.getString("historyFailureCode"))
                || !(row.get("expiresAt") instanceof Date expiry) || !expiry.after(now)) {
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.INVALID, Math.max(epoch, 0));
        }
        if (epoch < 0) { return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.INVALID, 0); }
        if (epoch >= MAX_ENRICHED_PUBLICATION_RECOVERY_EPOCHS) {
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.EXHAUSTED, epoch);
        }
        SourceEnrichedPublication verified = transaction(session -> {
            Document current = publications.find(session, eq("_id", publicationId)).first();
            if (current == null) { return SourceEnrichedPublication.unavailable(UNAVAILABLE); }
            SourceEnrichedPublication stored = readStoredEnrichedPublication(session, current);
            return stored.authority().state() == VERIFIED && activeFeatureOrderGate(session, stored.authority(),
                    stored.event().customerId(), current.getString("rawCommitment"))
                    ? stored : SourceEnrichedPublication.unavailable(UNAVAILABLE);
        }, SourceEnrichedPublication.unavailable(UNAVAILABLE));
        if (verified.authority().state() == UNAVAILABLE) {
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.UNAVAILABLE, epoch);
        }
        if (verified.authority().state() != VERIFIED || verified.event() == null
                || verified.published() != "PUBLISHED".equals(row.getString("state"))) {
            markEnrichedQuarantine(row, verified.authority().state() == CONFLICT ? "PAYLOAD_UNAVAILABLE" : "AUTHORITY_DENIED");
            return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.AUTHORITY_DENIED, epoch);
        }
        Date redriveAt = Date.from(clock.instant());
        int nextEpoch = epoch + 1;
        var epochFilter = epoch == 0 ? or(eq("recoveryEpoch", 0), exists("recoveryEpoch", false)) : eq("recoveryEpoch", epoch);
        try {
            var update = publications.updateOne(and(eq("_id", publicationId), eq("state", row.getString("state")),
                            eq("historyState", "QUARANTINED"), eq("historyFailureCode", row.getString("historyFailureCode")),
                            eq("historyAttempts", historyAttempts(row)), eq("receiptId", row.getString("receiptId")),
                            eq("enrichedCommitment", row.getString("enrichedCommitment")),
                            eq("eventId", row.getString("eventId")), eq("eventCreatedAt", row.getString("eventCreatedAt")),
                            eq("keyId", row.getString("keyId")), eq("nonce", row.get("nonce")),
                            eq("ciphertext", row.get("ciphertext")), epochFilter, gt("expiresAt", redriveAt)),
                    combine(set("historyState", "PENDING"), set("historyAttempts", 0),
                            set("historyNextAttemptAt", redriveAt), set("nextAttemptAt", redriveAt),
                            set("historyAppliedAt", null),
                            set("recoveryEpoch", nextEpoch),
                            set("lastRedriveAt", redriveAt), set("redriveReason", reason),
                            unset("historyFailureCode"), unset("historyLeaseToken"), unset("historyLeaseUntil")));
            if (update.wasAcknowledged() && update.getMatchedCount() == 1 && update.getModifiedCount() == 1) {
                return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.REQUEUED, nextEpoch);
            }
        } catch (RuntimeException unresolvedWrite) {
            // Read back the exact same published event after an unknown acknowledgement.
        }
        try {
            Document current = publications.find(eq("_id", publicationId)).first();
            if (current != null && Objects.equals(row.getString("state"), current.getString("state"))
                    && "PENDING".equals(current.getString("historyState"))
                    && publicationRecoveryEpoch(current) == nextEpoch && reason.equals(current.getString("redriveReason"))
                    && Objects.equals(redriveAt, current.getDate("lastRedriveAt"))
                    && Objects.equals(row.getString("receiptId"), current.getString("receiptId"))
                    && Objects.equals(row.getString("enrichedCommitment"), current.getString("enrichedCommitment"))
                    && Objects.equals(row.getString("eventId"), current.getString("eventId"))
                    && Objects.equals(row.getString("eventCreatedAt"), current.getString("eventCreatedAt"))
                    && Objects.equals(row.get("expiresAt"), current.get("expiresAt"))
                    && Objects.equals(row.get("keyId"), current.get("keyId"))
                    && Objects.equals(row.get("nonce"), current.get("nonce"))
                    && Objects.equals(row.get("ciphertext"), current.get("ciphertext"))) {
                return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.REQUEUED, nextEpoch);
            }
        } catch (RuntimeException unavailable) {
            // Keep the outcome unresolved if exact state cannot be read back.
        }
        return enrichedRedriveResult(SourceLineageAccess.EnrichedPublicationRedriveStatus.UNAVAILABLE, epoch);
    }

    private SourceEnrichedPublicationClaim claimNextEnrichedPublication() {
        Instant instant = clock.instant();
        Date now = Date.from(instant);
        String token = UUID.randomUUID().toString();
        return transaction(session -> {
            MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
            Document publishedRow = publications.findOneAndUpdate(session,
                    and(eq("state", "PUBLISHED"), eq("historyState", "PENDING"), gt("expiresAt", now),
                            historyRetryDue(now),
                            or(exists("historyLeaseUntil", false), lte("historyLeaseUntil", now))),
                    combine(set("historyLeaseToken", token),
                            set("historyLeaseUntil", Date.from(instant.plus(ENRICHED_PUBLICATION_LEASE)))),
                    new com.mongodb.client.model.FindOneAndUpdateOptions().sort(new Document("preparedAt", 1))
                            .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
            if (publishedRow != null) {
                SourceEnrichedPublication published = readStoredEnrichedPublication(session, publishedRow);
                if (published.authority().state() != VERIFIED || published.event() == null) {
                    if (published.authority().state() == UNAVAILABLE) {
                        publications.updateOne(session, and(eq("_id", publishedRow.getString("_id")),
                                        eq("state", "PUBLISHED"), eq("historyState", "PENDING"),
                                        eq("historyLeaseToken", token)),
                                combine(set("historyLeaseUntil", Date.from(instant.plusSeconds(30)))));
                        return claimStatus(SourceEnrichedPublicationClaim.Status.BUSY, UNAVAILABLE,
                                publicationAttempts(publishedRow));
                    }
                    String reason = published.authority().state() == CONFLICT
                            ? "PAYLOAD_UNAVAILABLE" : "AUTHORITY_DENIED";
                    var quarantined = publications.updateOne(session,
                            and(eq("_id", publishedRow.getString("_id")), eq("state", "PUBLISHED"),
                                    eq("historyState", "PENDING"), eq("historyLeaseToken", token)),
                            combine(set("historyState", "QUARANTINED"), set("failureCode", reason),
                                    unset("historyLeaseToken"), unset("historyLeaseUntil")));
                    if (!quarantined.wasAcknowledged() || quarantined.getMatchedCount() != 1) {
                        return claimStatus(SourceEnrichedPublicationClaim.Status.BUSY, UNAVAILABLE,
                                publicationAttempts(publishedRow));
                    }
                    return claimStatus(SourceEnrichedPublicationClaim.Status.QUARANTINED, published.authority().state(),
                            publicationAttempts(publishedRow));
                }
                if (!published.historyApplied() && !activeFeatureOrderGate(session, published.authority(),
                        published.event().customerId(), publishedRow.getString("rawCommitment"))) {
                    quarantineHistoryOrderMismatch(session, publishedRow, true, token);
                    return claimStatus(SourceEnrichedPublicationClaim.Status.QUARANTINED, UNAVAILABLE,
                            publicationAttempts(publishedRow));
                }
                return new SourceEnrichedPublicationClaim(SourceEnrichedPublicationClaim.Status.CLAIMED,
                        published.authority(), published.event(), publishedRow.getString("_id"), token,
                        publicationAttempts(publishedRow), true, false);
            }
            var eligible = enrichedRecoveryEligibility(now);
            Document exhausted = publications.findOneAndUpdate(session, and(eligible, eq("attempts", 8)),
                    combine(set("state", "RETRY_EXHAUSTED"), set("failureCode", "PUBLISH_FAILED"),
                            unset("leaseToken"), unset("leaseUntil"), unset("nextAttemptAt")),
                    new com.mongodb.client.model.FindOneAndUpdateOptions().sort(new Document("preparedAt", 1))
                            .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
            if (exhausted != null) { return claimStatus(SourceEnrichedPublicationClaim.Status.EXHAUSTED, UNAVAILABLE, 8); }

            Document row = publications.findOneAndUpdate(session,
                    and(eligible, or(exists("attempts", false), lt("attempts", MAX_ENRICHED_PUBLICATION_ATTEMPTS))),
                    combine(set("state", "PUBLISHING"), set("leaseToken", token),
                            set("leaseUntil", Date.from(instant.plus(ENRICHED_PUBLICATION_LEASE))),
                            inc("attempts", 1), set("nextAttemptAt", now), unset("failureCode")),
                    new com.mongodb.client.model.FindOneAndUpdateOptions().sort(new Document("preparedAt", 1))
                            .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
            if (row == null) { return claimUnavailable(UNAVAILABLE); }
            SourceEnrichedPublication stored = readStoredEnrichedPublication(session, row);
            if (stored.authority().state() != VERIFIED || stored.event() == null) {
                if (stored.authority().state() == UNAVAILABLE) {
                    releaseForRetry(session, row, "PUBLISH_FAILED");
                    return claimStatus(SourceEnrichedPublicationClaim.Status.BUSY, UNAVAILABLE, publicationAttempts(row));
                }
                quarantinePublication(session, row, stored.authority().state() == CONFLICT
                        ? "PAYLOAD_UNAVAILABLE" : "AUTHORITY_DENIED");
                return claimStatus(SourceEnrichedPublicationClaim.Status.QUARANTINED, stored.authority().state(), publicationAttempts(row));
            }
            if (!stored.historyApplied() && !activeFeatureOrderGate(session, stored.authority(),
                    stored.event().customerId(), row.getString("rawCommitment"))) {
                quarantineHistoryOrderMismatch(session, row, false, token);
                return claimStatus(SourceEnrichedPublicationClaim.Status.QUARANTINED, UNAVAILABLE,
                        publicationAttempts(row));
            }
            return new SourceEnrichedPublicationClaim(SourceEnrichedPublicationClaim.Status.CLAIMED, stored.authority(),
                    stored.event(), row.getString("_id"), token, publicationAttempts(row), false, stored.historyApplied());
        }, SourceEnrichedPublicationClaim.unavailable(UNAVAILABLE));
    }

    private void quarantineHistoryOrderMismatch(ClientSession session, Document row, boolean published, String token) {
        String state = published ? "PUBLISHED" : "PUBLISHING";
        String leaseField = published ? "historyLeaseToken" : "leaseToken";
        var filter = and(eq("_id", row.getString("_id")), eq("state", state), eq("historyState", "PENDING"),
                eq(leaseField, token));
        var updates = combine(set("historyState", "QUARANTINED"), set("historyFailureCode", "HISTORY_DATA_INVALID"),
                unset("historyLeaseToken"), unset("historyLeaseUntil"));
        if (!published) {
            updates = combine(updates, set("state", "PENDING"), inc("attempts", -1),
                    unset("leaseToken"), unset("leaseUntil"), unset("nextAttemptAt"));
        }
        var result = database.getCollection(ENRICHED_PUBLICATIONS).updateOne(session, filter, updates);
        if (!result.wasAcknowledged() || result.getMatchedCount() != 1 || result.getModifiedCount() != 1) {
            throw invalid("FEATURE_ORDER_GATE_MISMATCH_QUARANTINE_UNRESOLVED");
        }
    }

    private SourceEnrichedPublicationClaim claimPreparedRow(ClientSession session, Document row, SourceEnrichedPublication stored) {
        String state = row.getString("state");
        int attempts = publicationAttempts(row);
        if ("PUBLISHED".equals(state)) {
            if (stored.historyApplied()) {
                return new SourceEnrichedPublicationClaim(SourceEnrichedPublicationClaim.Status.ALREADY_PUBLISHED,
                        stored.authority(), null, null, null, attempts, true, true);
            }
            if ("QUARANTINED".equals(row.getString("historyState"))) {
                return claimStatus(SourceEnrichedPublicationClaim.Status.QUARANTINED, CONFLICT, attempts);
            }
            Instant instant = clock.instant();
            Date now = Date.from(instant);
            if (row.get("historyLeaseUntil") instanceof Date until && until.after(now)
                    || !historyRetryDue(row, now)) {
                return claimStatus(SourceEnrichedPublicationClaim.Status.BUSY, UNAVAILABLE, attempts);
            }
            String token = UUID.randomUUID().toString();
            Document claimed = database.getCollection(ENRICHED_PUBLICATIONS).findOneAndUpdate(session,
                    and(eq("_id", row.getString("_id")), eq("state", "PUBLISHED"), eq("historyState", "PENDING"),
                            gt("expiresAt", now), historyRetryDue(now),
                            or(exists("historyLeaseUntil", false), lte("historyLeaseUntil", now))),
                    combine(set("historyLeaseToken", token), set("historyLeaseUntil", Date.from(instant.plus(ENRICHED_PUBLICATION_LEASE)))),
                    new com.mongodb.client.model.FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
            if (claimed == null) { return claimStatus(SourceEnrichedPublicationClaim.Status.BUSY, UNAVAILABLE, attempts); }
            return new SourceEnrichedPublicationClaim(SourceEnrichedPublicationClaim.Status.CLAIMED, stored.authority(),
                    stored.event(), claimed.getString("_id"), token, attempts, true, false);
        }
        if ("RETRY_EXHAUSTED".equals(state)) {
            return claimStatus(SourceEnrichedPublicationClaim.Status.EXHAUSTED, UNAVAILABLE, attempts);
        }
        if ("QUARANTINED".equals(state)) {
            return claimStatus(SourceEnrichedPublicationClaim.Status.QUARANTINED, CONFLICT, attempts);
        }
        Instant instant = clock.instant();
        Date now = Date.from(instant);
        if (!("PENDING".equals(state) || "PUBLISHING".equals(state))) {
            return claimStatus(SourceEnrichedPublicationClaim.Status.BUSY, UNAVAILABLE, attempts);
        }
        if ("QUARANTINED".equals(row.getString("historyState"))) {
            return claimStatus(SourceEnrichedPublicationClaim.Status.QUARANTINED, CONFLICT, attempts);
        }
        if ("PENDING".equals(state) && row.get("nextAttemptAt") instanceof Date next && next.after(now)
                || "PUBLISHING".equals(state) && row.get("leaseUntil") instanceof Date until && until.after(now)) {
            return claimStatus(SourceEnrichedPublicationClaim.Status.BUSY, UNAVAILABLE, attempts);
        }
        MongoCollection<Document> publications = database.getCollection(ENRICHED_PUBLICATIONS);
        var eligible = and(eq("_id", row.getString("_id")), eq("state", state), gt("expiresAt", now),
                or(exists("attempts", false), lt("attempts", MAX_ENRICHED_PUBLICATION_ATTEMPTS)),
                "PENDING".equals(state) ? or(exists("nextAttemptAt", false), lte("nextAttemptAt", now))
                        : or(exists("leaseUntil", false), lte("leaseUntil", now)));
        if (attempts >= MAX_ENRICHED_PUBLICATION_ATTEMPTS) {
            var terminal = publications.updateOne(session, and(eq("_id", row.getString("_id")), eq("state", state), gt("expiresAt", now),
                            eq("attempts", attempts), "PENDING".equals(state)
                                    ? or(exists("nextAttemptAt", false), lte("nextAttemptAt", now))
                                    : or(exists("leaseUntil", false), lte("leaseUntil", now))),
                    combine(set("state", "RETRY_EXHAUSTED"), set("failureCode", "PUBLISH_FAILED"),
                            unset("leaseToken"), unset("leaseUntil"), unset("nextAttemptAt")));
            return claimStatus(terminal.wasAcknowledged() && terminal.getMatchedCount() == 1
                    ? SourceEnrichedPublicationClaim.Status.EXHAUSTED : SourceEnrichedPublicationClaim.Status.BUSY,
                    UNAVAILABLE, attempts);
        }
        String token = UUID.randomUUID().toString();
        Document claimed = publications.findOneAndUpdate(session, eligible,
                combine(set("state", "PUBLISHING"), set("leaseToken", token),
                        set("leaseUntil", Date.from(instant.plus(ENRICHED_PUBLICATION_LEASE))), set("nextAttemptAt", now),
                        inc("attempts", 1), unset("failureCode")),
                new com.mongodb.client.model.FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
        if (claimed == null) { return claimStatus(SourceEnrichedPublicationClaim.Status.BUSY, UNAVAILABLE, attempts); }
        return new SourceEnrichedPublicationClaim(SourceEnrichedPublicationClaim.Status.CLAIMED, stored.authority(),
                stored.event(), claimed.getString("_id"), token, publicationAttempts(claimed), false, stored.historyApplied());
    }

    private static org.bson.conversions.Bson enrichedRecoveryEligibility(Date now) {
        return and(gt("expiresAt", now), or(exists("nextAttemptAt", false), lte("nextAttemptAt", now)),
                or(and(eq("historyState", "PENDING"), historyRetryDue(now)),
                        and(eq("historyState", "APPLIED"), historyRetryDue(now))),
                or(eq("state", "PENDING"), and(eq("state", "PUBLISHING"),
                        or(exists("leaseUntil", false), lte("leaseUntil", now)))));
    }

    private static org.bson.conversions.Bson historyRetryDue(Date now) {
        return or(exists("historyNextAttemptAt", false), lte("historyNextAttemptAt", now));
    }

    private static boolean historyRetryDue(Document row, Date now) {
        return !(row.get("historyNextAttemptAt") instanceof Date next) || !next.after(now);
    }

    private void releaseForRetry(ClientSession session, Document row, String reason) {
        int attempts = publicationAttempts(row);
        if (attempts >= MAX_ENRICHED_PUBLICATION_ATTEMPTS) {
            database.getCollection(ENRICHED_PUBLICATIONS).updateOne(session,
                    and(eq("_id", row.getString("_id")), eq("state", "PUBLISHING"), eq("leaseToken", row.getString("leaseToken"))),
                    combine(set("state", "RETRY_EXHAUSTED"), set("failureCode", reason), unset("leaseToken"),
                            unset("leaseUntil"), unset("nextAttemptAt")));
        } else {
            database.getCollection(ENRICHED_PUBLICATIONS).updateOne(session,
                    and(eq("_id", row.getString("_id")), eq("state", "PUBLISHING"), eq("leaseToken", row.getString("leaseToken"))),
                    combine(set("state", "PENDING"), set("failureCode", reason),
                            set("nextAttemptAt", Date.from(clock.instant().plusSeconds(30))),
                            unset("leaseToken"), unset("leaseUntil")));
        }
    }

    private void quarantinePublication(ClientSession session, Document row, String reason) {
        database.getCollection(ENRICHED_PUBLICATIONS).updateOne(session,
                and(eq("_id", row.getString("_id")), eq("state", "PUBLISHING"), eq("leaseToken", row.getString("leaseToken"))),
                combine(set("state", "QUARANTINED"), set("failureCode", reason),
                        unset("leaseToken"), unset("leaseUntil"), unset("nextAttemptAt")));
    }

    private static boolean validClaimIdentity(Document row, SourceEnrichedPublicationClaim claim) {
        return row != null && claim.publicationId().equals(row.getString("_id"))
                && claim.authority().receiptId().equals(row.getString("receiptId"))
                && SourceEventCommitment.enriched(claim.event()).equals(row.getString("enrichedCommitment"))
                && claim.event().eventId().equals(row.getString("eventId"))
                && claim.event().createdAt().toString().equals(row.getString("eventCreatedAt"));
    }

    private SourceEnrichedPublication readStoredEnrichedPublication(ClientSession session, Document row) {
        Instant now = clock.instant();
        String state = row.getString("state");
        Date publishedAt = row.getDate("publishedAt");
        boolean published = "PUBLISHED".equals(state);
        int historyAttemptCount = historyAttempts(row);
        Object historyNextAttempt = row.get("historyNextAttemptAt");
        if (row.getInteger("schemaVersion", 0) != 1
                || !enrichedPublicationId(row.getString("rawCommitment")).equals(row.getString("_id"))
                || !(published || Set.of("PENDING", "PUBLISHING", "RETRY_EXHAUSTED", "QUARANTINED").contains(state))
                || published != (publishedAt != null) || !row.getDate("expiresAt").toInstant().isAfter(now)
                || !("PENDING".equals(row.getString("historyState")) || "APPLIED".equals(row.getString("historyState"))
                || "QUARANTINED".equals(row.getString("historyState")))
                || historyAttemptCount < 0 || historyNextAttempt != null && !(historyNextAttempt instanceof Date)
                || ("APPLIED".equals(row.getString("historyState")) && row.getDate("historyAppliedAt") == null)
                || ("PENDING".equals(row.getString("historyState")) && row.getDate("historyAppliedAt") != null)) {
            return SourceEnrichedPublication.unavailable(CONFLICT);
        }
        try {
            TransactionEnrichedEvent event = enrichedPublicationCipher.decrypt(row);
            String commitment = SourceEventCommitment.enriched(event);
            if (!event.eventId().equals(row.getString("eventId")) || !event.createdAt().toString().equals(row.getString("eventCreatedAt"))
                    || !commitment.equals(row.getString("enrichedCommitment")) || event.featureSnapshot() == null
                    || !FEATURES.equals(event.featureSnapshot().keySet())) { return SourceEnrichedPublication.unavailable(CONFLICT); }
            String linkId = SourceEventCommitment.eventIdentity("enriched", event.eventId(), event.createdAt());
            Document link = database.getCollection(ENRICHED).find(session, eq("_id", linkId)).first();
            if (link == null || !row.getString("rawCommitment").equals(link.getString("parentCommitment"))
                    || !row.getString("receiptId").equals(link.getString("receiptId"))
                    || !row.getDate("expiresAt").equals(link.getDate("expiresAt"))) { return SourceEnrichedPublication.unavailable(CONFLICT); }
            SourceLineage authority = enrichedLink(session, link, commitment);
            return authority.state() == VERIFIED ? new SourceEnrichedPublication(authority, event, published,
                    "APPLIED".equals(row.getString("historyState")))
                    : SourceEnrichedPublication.unavailable(authority.state());
        } catch (RuntimeException invalidPayload) {
            return SourceEnrichedPublication.unavailable(CONFLICT);
        }
    }

    private SourceLineage reconcilePublishedClaim(SourceEnrichedPublicationClaim claim) {
        try {
            Document row = database.getCollection(ENRICHED_PUBLICATIONS).find(eq("_id", claim.publicationId())).first();
            if (row == null || !"PUBLISHED".equals(row.getString("state"))
                    || !SourceEventCommitment.enriched(claim.event()).equals(row.getString("enrichedCommitment"))
                    || row.getDate("publishedAt") == null) { return absent(UNAVAILABLE); }
            return transaction(session -> readStoredEnrichedPublication(session,
                    database.getCollection(ENRICHED_PUBLICATIONS).find(session, eq("_id", claim.publicationId())).first()).authority());
        } catch (RuntimeException unavailable) { return absent(UNAVAILABLE); }
    }

    private static SourceEnrichedPublicationClaim claimUnavailable(SourceLineage.State state) {
        return SourceEnrichedPublicationClaim.unavailable(state);
    }

    private static SourceEnrichedPublicationClaim claimStatus(SourceEnrichedPublicationClaim.Status status,
                                                               SourceLineage.State authority, int attempts) {
        return new SourceEnrichedPublicationClaim(status, SourceLineage.unavailable(authority), null, null, null,
                Math.max(0, Math.min(MAX_ENRICHED_PUBLICATION_ATTEMPTS, attempts)), false, false);
    }

    private static int publicationAttempts(Document row) {
        Integer attempts = row.getInteger("attempts");
        return attempts == null ? 0 : Math.max(0, Math.min(MAX_ENRICHED_PUBLICATION_ATTEMPTS, attempts));
    }

    private static int historyAttempts(Document row) {
        Object attempts = row.get("historyAttempts");
        if (attempts == null) { return 0; }
        return attempts instanceof Integer value && value >= 0 && value <= MAX_ENRICHED_HISTORY_ATTEMPTS ? value : -1;
    }

    private static long historyRetryDelaySeconds(int attempt) {
        return Math.min(300, 1L << Math.max(0, Math.min(attempt - 1, 8)));
    }

    private static long publicationAge(Document row, Instant now) {
        try {
            Instant prepared = row.get("preparedAt") instanceof Date date ? date.toInstant()
                    : canonicalTime(row.getString("eventCreatedAt"));
            return Math.max(0, Duration.between(prepared, now).toSeconds());
        } catch (RuntimeException invalidTime) { return 0; }
    }

    private void markEnrichedQuarantine(Document original, String reason) {
        if (!Set.of("AUTHORITY_DENIED", "PAYLOAD_UNAVAILABLE").contains(reason)) { return; }
        try {
            database.getCollection(ENRICHED_PUBLICATIONS).updateOne(and(eq("_id", original.getString("_id")),
                            eq("state", "RETRY_EXHAUSTED"), eq("failureCode", "PUBLISH_FAILED"),
                            eq("enrichedCommitment", original.getString("enrichedCommitment")),
                            eq("ciphertext", original.get("ciphertext"))),
                    combine(set("state", "QUARANTINED"), set("failureCode", reason)));
        } catch (RuntimeException unavailable) {
            // Invalid or no-longer-authorized publication remains terminal and is never claimed by automatic recovery.
        }
    }

    private static int publicationRecoveryEpoch(Document row) {
        if (!row.containsKey("recoveryEpoch")) { return 0; }
        Object value = row.get("recoveryEpoch");
        if (!(value instanceof Integer epoch) || epoch < 0 || epoch > MAX_ENRICHED_PUBLICATION_RECOVERY_EPOCHS) { return -1; }
        return epoch;
    }

    private static SourceLineageAccess.EnrichedPublicationRedriveResult enrichedRedriveResult(
            SourceLineageAccess.EnrichedPublicationRedriveStatus status, int epoch) {
        return new SourceLineageAccess.EnrichedPublicationRedriveResult(status,
                Math.max(0, Math.min(MAX_ENRICHED_PUBLICATION_RECOVERY_EPOCHS, epoch)));
    }

    private SourceEnrichedPublication readEnrichedPublication(ClientSession session, TransactionRawEvent input,
                                                               SourceLineage parent, Document row) {
        String expectedId = enrichedPublicationId(parent.eventCommitment());
        Date publishedAt = row.getDate("publishedAt");
        boolean published = "PUBLISHED".equals(row.getString("state"));
        if (row.getInteger("schemaVersion", 0) != 1 || !expectedId.equals(row.getString("_id"))
                || !parent.receiptId().equals(row.getString("receiptId"))
                || !parent.eventCommitment().equals(row.getString("rawCommitment"))
                || !row.getDate("expiresAt").toInstant().isAfter(clock.instant())
                || published != (publishedAt != null)
                || !("PENDING".equals(row.getString("historyState")) || "APPLIED".equals(row.getString("historyState")))
                || ("APPLIED".equals(row.getString("historyState")) != (row.getDate("historyAppliedAt") != null))
                || !(published || Set.of("PENDING", "PUBLISHING", "RETRY_EXHAUSTED", "QUARANTINED").contains(row.getString("state")))) {
            return SourceEnrichedPublication.unavailable(CONFLICT);
        }
        try {
            TransactionEnrichedEvent event = enrichedPublicationCipher.decrypt(row);
            String commitment = SourceEventCommitment.enriched(event);
            if (!event.eventId().equals(row.getString("eventId"))
                    || !event.createdAt().toString().equals(row.getString("eventCreatedAt"))
                    || !commitment.equals(row.getString("enrichedCommitment")) || !sameInterpretation(input, event)
                    || event.featureSnapshot() == null || !FEATURES.equals(event.featureSnapshot().keySet())) {
                return SourceEnrichedPublication.unavailable(CONFLICT);
            }
            Document link = database.getCollection(ENRICHED).find(session,
                    eq("_id", SourceEventCommitment.eventIdentity("enriched", event.eventId(), event.createdAt()))).first();
            if (link == null || !parent.eventCommitment().equals(link.getString("parentCommitment"))
                    || !parent.receiptId().equals(link.getString("receiptId"))
                    || !row.getDate("expiresAt").equals(link.getDate("expiresAt"))) {
                return SourceEnrichedPublication.unavailable(CONFLICT);
            }
            SourceLineage authority = enrichedLink(session, link, commitment);
            if (authority.state() != VERIFIED) { return SourceEnrichedPublication.unavailable(authority.state()); }
            return new SourceEnrichedPublication(authority, event, published, "APPLIED".equals(row.getString("historyState")));
        } catch (RuntimeException unavailable) {
            return SourceEnrichedPublication.unavailable(UNAVAILABLE);
        }
    }

    private static String enrichedPublicationId(String rawCommitment) {
        return SourceReceiptIdentity.digest("source-enriched-publication-id/v1", rawCommitment);
    }

    @Override public SourceLineage enriched(TransactionEnrichedEvent event) {
        return transaction(session -> enriched(session, event));
    }

    /** Exact private receipt/RAW/ENRICHED authorization without retaining another copy of either event. */
    public SourceLineage enrichedBinding(String receiptId, String rawCommitment, String enrichedCommitment) {
        if (!hash(receiptId) || !hash(rawCommitment) || !hash(enrichedCommitment)) { return absent(UNVERIFIED); }
        return transaction(session -> {
            Document link = database.getCollection(ENRICHED).find(session,
                    and(eq("receiptId", receiptId), eq("eventCommitment", enrichedCommitment))).first();
            if (link == null) { return absent(UNAVAILABLE); }
            if (!rawCommitment.equals(link.getString("parentCommitment"))) { return absent(CONFLICT); }
            return enrichedLink(session, link, enrichedCommitment);
        });
    }

    /** Resolves a bounded set of exact source bindings in one source snapshot, while rechecking each receipt policy. */
    public List<SourceLineage> enrichedBindings(List<EnrichedBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) { return List.of(); }
        if (bindings.size() > MAX_ENRICHED_BINDING_BATCH) {
            return Collections.nCopies(bindings.size(), absent(UNAVAILABLE));
        }
        List<SourceLineage> results = new ArrayList<>(Collections.nCopies(bindings.size(), absent(UNVERIFIED)));
        List<EnrichedBinding> valid = new ArrayList<>(bindings.size());
        int[] positions = new int[bindings.size()];
        int validCount = 0;
        for (int index = 0; index < bindings.size(); index++) {
            EnrichedBinding binding = bindings.get(index);
            if (binding == null || !hash(binding.receiptId()) || !hash(binding.rawCommitment())
                    || !hash(binding.enrichedCommitment())) { continue; }
            valid.add(binding);
            positions[validCount++] = index;
        }
        if (valid.isEmpty()) { return List.copyOf(results); }
        List<SourceLineage> resolved = transaction(session -> enrichedBindings(session, valid),
                Collections.nCopies(valid.size(), absent(UNAVAILABLE)));
        for (int index = 0; index < validCount; index++) { results.set(positions[index], resolved.get(index)); }
        return List.copyOf(results);
    }

    private List<SourceLineage> enrichedBindings(ClientSession session, List<EnrichedBinding> bindings) {
        if (session == null || !session.hasActiveTransaction()) {
            return Collections.nCopies(bindings.size(), absent(UNAVAILABLE));
        }
        List<String> enrichedCommitments = bindings.stream().map(EnrichedBinding::enrichedCommitment).distinct().toList();
        Map<String, Document> links = documentsBy(session, ENRICHED, "eventCommitment", enrichedCommitments);
        if (links == null) { return Collections.nCopies(bindings.size(), absent(UNAVAILABLE)); }

        List<String> rawCommitments = links.values().stream().map(row -> row.getString("parentCommitment"))
                .filter(MongoSourceLineage::hash).distinct().toList();
        List<String> receiptIds = links.values().stream().map(row -> row.getString("receiptId"))
                .filter(MongoSourceLineage::hash).distinct().toList();
        Map<String, Document> raw = documentsBy(session, RAW, "eventCommitment", rawCommitments);
        Map<String, Document> receipts = documentsBy(session, RECEIPTS, "_id", receiptIds);
        Map<String, Document> identities = documentsBy(session, IDENTITIES, "_id", receiptIds);
        if (raw == null || receipts == null || identities == null) {
            return Collections.nCopies(bindings.size(), absent(UNAVAILABLE));
        }

        List<SourceLineage> results = new ArrayList<>(bindings.size());
        for (EnrichedBinding binding : bindings) {
            Document link = links.get(binding.enrichedCommitment());
            if (link == null || !binding.receiptId().equals(link.getString("receiptId"))) {
                results.add(absent(UNAVAILABLE));
                continue;
            }
            if (!binding.rawCommitment().equals(link.getString("parentCommitment"))) {
                results.add(absent(CONFLICT));
                continue;
            }
            Document rawRow = raw.get(binding.rawCommitment());
            Receipt receipt = receipt(identities.get(binding.receiptId()), receipts.get(binding.receiptId()), binding.receiptId());
            results.add(enrichedLink(link, rawRow, receipt, binding.enrichedCommitment()));
        }
        return List.copyOf(results);
    }

    private Map<String, Document> documentsBy(ClientSession session, String collection, String field, List<String> values) {
        if (values.isEmpty()) { return Map.of(); }
        List<Document> rows = database.getCollection(collection).find(session, in(field, values))
                .batchSize(MAX_ENRICHED_BINDING_BATCH).into(new ArrayList<>());
        Map<String, Document> result = new HashMap<>(rows.size());
        for (Document row : rows) {
            String value = row.getString(field);
            if (value == null || result.putIfAbsent(value, row) != null) { return null; }
        }
        return result;
    }

    private static boolean hash(String value) { return value != null && COMMITMENT_HASH.matcher(value).matches(); }

    public record EnrichedBinding(String receiptId, String rawCommitment, String enrichedCommitment) { }

    SourceLineage enriched(ClientSession session, TransactionEnrichedEvent event) {
        if (session == null || !session.hasActiveTransaction()) { return absent(UNAVAILABLE); }
        String commitment = SourceEventCommitment.enriched(event);
        Document link = database.getCollection(ENRICHED).find(session,
                eq("_id", SourceEventCommitment.eventIdentity("enriched", event.eventId(), event.createdAt()))).first();
        if (link == null) { return absent(UNVERIFIED); }
        if (!validLink(link, event.eventId(), event.createdAt(), commitment)) { return absent(CONFLICT); }
        return enrichedLink(session, link, commitment);
    }

    private SourceLineage enrichedLink(ClientSession session, Document link, String commitment) {
        Document raw = database.getCollection(RAW).find(session, eq("eventCommitment", link.getString("parentCommitment"))).first();
        Receipt receipt = receipt(session, link.getString("receiptId"));
        return enrichedLink(link, raw, receipt, commitment);
    }

    private SourceLineage enrichedLink(Document link, Document raw, Receipt receipt, String commitment) {
        if (link == null) { return absent(CONFLICT); }
        String linkEventId = link.getString("eventId");
        Instant linkCreatedAt = canonicalTime(link.getString("eventCreatedAt"));
        if (!validLink(link, linkEventId, linkCreatedAt, commitment)
                || !SourceEventCommitment.eventIdentity("enriched", linkEventId, linkCreatedAt).equals(link.getString("_id"))) {
            return absent(CONFLICT);
        }
        if (raw == null || raw.getInteger("schemaVersion", 0) != 1 || !"BOUND".equals(raw.getString("state"))
                || !SourceEventCommitment.eventIdentity("raw", raw.getString("eventId"), canonicalTime(raw.getString("eventCreatedAt")))
                .equals(raw.getString("_id")) || !Objects.equals(raw.get("receiptId"), link.get("receiptId"))) {
            return absent(UNAVAILABLE);
        }
        if (receipt.state != VERIFIED) { return absent(receipt.state); }
        if (!receipt.bindingCommitment.equals(raw.getString("parentCommitment"))
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
        return receipt(identity, row, id);
    }

    private Receipt receipt(Document identity, Document row, String id) {
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
        String receiptId = SourceReceiptIdentity.identity(scope);
        String binding = SourceReceiptIdentity.binding(receiptId, scope.signingKeyVersion(), scope.eventTime(), scope.purpose(),
                scope.audience(), scope.payloadCommitment(), row.getString("keyCommitment"));
        if (!receiptId.equals(id) || !binding.equals(identity.getString("bindingCommitment"))
                || !binding.equals(row.getString("bindingCommitment")) || !Objects.equals(identity.get("expiresAt"), row.get("expiresAt"))
                || !Objects.equals(identity.get("admittedAt"), row.get("admittedAt"))
                || verifiedAt.toEpochMilli() > admitted.toEpochMilli() || admitted.isAfter(clock.instant())) {
            return Receipt.absent(CONFLICT);
        }
        if (policy.receiptPolicyDenial(scope, row.getString("keyCommitment"), row.getString("policyVersion"), verifiedAt) != null) {
            return Receipt.absent(REVOKED);
        }
        return new Receipt(VERIFIED, id, scope, row.getString("keyCommitment"), row.getString("policyVersion"), expiry, binding);
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
        return transaction(work, absent(UNAVAILABLE));
    }
    private <T> T transaction(Function<ClientSession, T> work, T unavailable) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TRANSACTION);
                try {
                    T result = work.apply(session);
                    for (int commitAttempt = 0; ; commitAttempt++) {
                        try { session.commitTransaction(); return result; }
                        catch (MongoException failure) {
                            if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) || commitAttempt == 2) {
                                if (failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) { return unavailable; }
                                throw failure;
                            }
                        }
                    }
                } catch (RuntimeException failure) {
                    if (failure instanceof MongoException mongo && mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                        return unavailable;
                    }
                    try { if (session.hasActiveTransaction()) { session.abortTransaction(); } } catch (RuntimeException ignored) { }
                    if (failure instanceof MongoException mongo && (mongo.getCode() == 11000
                            || mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) && attempt < 2) { continue; }
                    return unavailable;
                }
            } catch (RuntimeException failure) { return unavailable; }
        }
        return unavailable;
    }
    private static Instant canonicalTime(String value) {
        Instant time = Instant.parse(value);
        if (!time.toString().equals(value)) { throw invalid("TIME_INVALID"); }
        return time;
    }
    private static SourceLineage absent(SourceLineage.State state) { return SourceLineage.unavailable(state); }
    private static IllegalStateException invalid(String code) { return new IllegalStateException("SOURCE_LINEAGE_" + code); }
    private record Receipt(SourceLineage.State state, String id, SourceScope scope, String keyCommitment,
                           String policyVersion, Instant expiresAt, String bindingCommitment) {
        static Receipt absent(SourceLineage.State state) { return new Receipt(state, null, null, null, null, null, null); }
    }
}
