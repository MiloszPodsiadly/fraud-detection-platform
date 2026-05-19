package com.frauddetection.alert.suspicious;

import org.bson.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class SuspiciousTransactionIndexTestSupport {

    static final String CURSOR_INDEX = "idx_suspicious_tx_cursor_detected_at_id_desc";
    static final String STATUS_INDEX = "idx_suspicious_tx_status_cursor";
    static final String RISK_INDEX = "idx_suspicious_tx_risk_cursor";
    static final String CUSTOMER_INDEX = "idx_suspicious_tx_customer_cursor";
    static final String ALERT_INDEX = "idx_suspicious_tx_alert_cursor";
    static final String IDEMPOTENCY_INDEX = "suspicious_transaction_source_event_unique_idx";

    static final Set<String> EXPECTED_INDEX_NAMES = Set.of(
            CURSOR_INDEX,
            STATUS_INDEX,
            RISK_INDEX,
            CUSTOMER_INDEX,
            ALERT_INDEX,
            IDEMPOTENCY_INDEX
    );

    private SuspiciousTransactionIndexTestSupport() {
    }

    static Map<String, CompoundIndex> indexesByName() {
        CompoundIndexes indexes = SuspiciousTransactionDocument.class.getAnnotation(CompoundIndexes.class);
        return Arrays.stream(indexes.value())
                .collect(Collectors.toMap(CompoundIndex::name, index -> index));
    }

    static LinkedHashMap<String, Integer> keys(String indexName) {
        String definition = indexesByName().get(indexName).def().replace('\'', '"');
        Document document = Document.parse(definition);
        LinkedHashMap<String, Integer> keys = new LinkedHashMap<>();
        document.forEach((key, value) -> keys.put(key, ((Number) value).intValue()));
        return keys;
    }

    static List<String> indexedFieldNames() {
        return Arrays.stream(SuspiciousTransactionDocument.class.getDeclaredFields())
                .filter(field -> field.getAnnotation(Indexed.class) != null)
                .map(Field::getName)
                .toList();
    }
}
