package com.frauddetection.alert.persistence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ScoringOccurrenceFingerprint {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private ScoringOccurrenceFingerprint() {
    }

    public static String from(TransactionScoredEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_EVENT_REQUIRED");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(OBJECT_MAPPER.writeValueAsBytes(event)));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("AUTHORITATIVE_SCORING_EVENT_CANONICALIZATION_FAILED");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SCORING_OCCURRENCE_HASH_ALGORITHM_UNAVAILABLE");
        }
    }
}
