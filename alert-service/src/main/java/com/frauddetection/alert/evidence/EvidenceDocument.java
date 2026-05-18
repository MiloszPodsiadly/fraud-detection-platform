package com.frauddetection.alert.evidence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Document(collection = "fraud_evidence")
public class EvidenceDocument {

    @Id
    private String evidenceId;

    @Indexed
    private String transactionId;

    @Indexed
    private String alertId;

    @Indexed
    private String customerId;

    @Indexed
    private String correlationId;

    @Indexed
    private String sourceEventId;

    @Indexed
    private String reasonCode;

    @Indexed
    private EvidenceSource source;

    @Indexed
    private EvidenceStatus status;

    @Indexed
    private Instant createdAt;

    private EvidenceEntityType entityType;
    private String entityId;
    private EvidenceType evidenceType;
    private EvidenceSeverity severity;
    private String title;
    private String description;
    private String value;
    private String baselineValue;
    private Map<String, Object> attributes = new LinkedHashMap<>();
    private String scoringStrategy;
    private String modelName;
    private String modelVersion;
    private Instant observedAt;

    protected EvidenceDocument() {
    }

    public static EvidenceDocument create(EvidenceSource source, EvidenceStatus status) {
        EvidenceDocument document = new EvidenceDocument();
        document.setSource(Objects.requireNonNull(source, "source is required"));
        document.setStatus(Objects.requireNonNull(status, "status is required"));
        return document;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public EvidenceSource getSource() {
        return source;
    }

    public void setSource(EvidenceSource source) {
        this.source = Objects.requireNonNull(source, "source is required");
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public void setStatus(EvidenceStatus status) {
        this.status = Objects.requireNonNull(status, "status is required");
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public EvidenceEntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(EvidenceEntityType entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(EvidenceType evidenceType) {
        this.evidenceType = evidenceType;
    }

    public EvidenceSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(EvidenceSeverity severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getBaselineValue() {
        return baselineValue;
    }

    public void setBaselineValue(String baselineValue) {
        this.baselineValue = baselineValue;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    public String getScoringStrategy() {
        return scoringStrategy;
    }

    public void setScoringStrategy(String scoringStrategy) {
        this.scoringStrategy = scoringStrategy;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }
}
