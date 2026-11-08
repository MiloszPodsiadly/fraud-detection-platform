package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceSignedInput;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.config.KafkaTopicProperties;
import com.frauddetection.ingest.mapper.TransactionRequestMapper;
import com.frauddetection.ingest.messaging.TransactionRawEventProducer;
import com.frauddetection.ingest.observability.CorrelationIdProvider;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Separate ingress: a rejected attestation never silently falls back to ordinary ingestion. */
@Service
public class AttestedTransactionIngestService {
    public static final String PURPOSE = "TRANSACTION_SOURCE_ATTESTATION";
    public static final String AUDIENCE = "fraud-platform.ingest.v1";
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(32).maxStringLength(SourceSignedInput.MAX_PAYLOAD_BYTES).maxNumberLength(128).build()).build())
            .findAndAddModules().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final TransactionRequestMapper mapper;
    private final SourceSignatureVerifier verifier;
    private final SourceReceiptStore receipts;
    private final MongoSourceLineage lineage;
    private final TransactionRawEventProducer publisher;
    private final CorrelationIdProvider correlation;
    private final KafkaTopicProperties topics;
    private final Validator validator;

    @Autowired
    public AttestedTransactionIngestService(TransactionRequestMapper mapper, SourceSignatureVerifier verifier,
                                            ObjectProvider<SourceReceiptStore> receipts, ObjectProvider<MongoSourceLineage> lineage,
                                            TransactionRawEventProducer publisher, CorrelationIdProvider correlation,
                                            KafkaTopicProperties topics, Validator validator) {
        this(mapper, verifier, receipts.getIfAvailable(), lineage.getIfAvailable(), publisher, correlation, topics, validator);
    }

    public AttestedTransactionIngestService(TransactionRequestMapper mapper, SourceSignatureVerifier verifier,
                                            SourceReceiptStore receipts, MongoSourceLineage lineage, TransactionRawEventProducer publisher,
                                            CorrelationIdProvider correlation, KafkaTopicProperties topics, Validator validator) {
        this.mapper = mapper; this.verifier = verifier; this.receipts = receipts; this.lineage = lineage;
        this.publisher = publisher; this.correlation = correlation; this.topics = topics; this.validator = validator;
    }

    public IngestTransactionResponse ingest(String encodedProof, byte[] payload) {
        if (payload == null || payload.length == 0) { throw reject(HttpStatus.BAD_REQUEST, "SOURCE_TRANSACTION_REQUIRED"); }
        if (payload.length > SourceSignedInput.MAX_PAYLOAD_BYTES) { throw reject(HttpStatus.PAYLOAD_TOO_LARGE, "SOURCE_TRANSACTION_TOO_LARGE"); }
        payload = payload.clone();
        byte[] proof;
        try {
            if (encodedProof == null || encodedProof.length() > 5_462) { throw new IllegalArgumentException(); }
            proof = Base64.getUrlDecoder().decode(encodedProof);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(proof).equals(encodedProof)) { throw new IllegalArgumentException(); }
        } catch (RuntimeException malformed) { throw reject(HttpStatus.FORBIDDEN, "SOURCE_PROOF_INVALID"); }
        var verification = verifier.verify(proof, payload, new SourceSignatureVerifier.Context(PURPOSE, AUDIENCE));
        if (!(verification instanceof SourceSignatureVerifier.Verified verified)) {
            throw reject(HttpStatus.FORBIDDEN, "SOURCE_PROOF_NOT_ADMITTED");
        }
        IngestTransactionRequest request;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
            request = MAPPER.readValue(json, IngestTransactionRequest.class);
            if (request == null || !validator.validate(request).isEmpty()) { throw new IllegalArgumentException(); }
        } catch (Exception malformed) { throw reject(HttpStatus.BAD_REQUEST, "SOURCE_TRANSACTION_INVALID"); }
        if (receipts == null || lineage == null) { throw reject(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_LINEAGE_UNAVAILABLE"); }
        var receipt = receipts.admit(verified);
        if (receipt.status() != SourceReceiptStore.Status.ADMITTED && receipt.status() != SourceReceiptStore.Status.EXISTING) {
            HttpStatus status = receipt.status() == SourceReceiptStore.Status.CONFLICT ? HttpStatus.CONFLICT
                    : receipt.status() == SourceReceiptStore.Status.REVOKED ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
            throw reject(status, "SOURCE_RECEIPT_" + receipt.status().name());
        }
        String correlationId = correlation.currentOrGenerate();
        var event = mapper.toEvent(request, correlationId);
        if (lineage.bindRaw(verified, receipt.receipt().id(), event).state() != SourceLineage.State.VERIFIED) {
            throw reject(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_LINEAGE_UNAVAILABLE");
        }
        try { publisher.publish(event); }
        catch (RuntimeException uncertain) { throw reject(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_RAW_PUBLICATION_UNRESOLVED"); }
        return new IngestTransactionResponse(event.transactionId(), event.eventId(), correlationId, topics.transactionRaw(), Instant.now(), "ACCEPTED");
    }
    private static SourceIngestException reject(HttpStatus status, String reason) { return new SourceIngestException(status, reason); }
}
