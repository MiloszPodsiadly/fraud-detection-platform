package com.frauddetection.common.events.reason;

import com.frauddetection.common.events.features.FraudFeatureContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum ReasonCode {
    UNKNOWN(
            "UNKNOWN",
            ReasonCodeCategory.UNSUPPORTED,
            "Unsupported reason code",
            "Compatibility marker for unsupported, malformed, or future reason-code input. It is not a scoring signal."
    ),
    DEVICE_NOVELTY(
            FraudFeatureContract.FLAG_DEVICE_NOVELTY,
            ReasonCodeCategory.DEVICE_AND_NETWORK,
            "Device novelty",
            "Device context differed from previously observed customer behavior."
    ),
    COUNTRY_MISMATCH(
            FraudFeatureContract.FLAG_COUNTRY_MISMATCH,
            ReasonCodeCategory.CUSTOMER_BEHAVIOR,
            "Country mismatch",
            "Transaction geography differed from expected customer context."
    ),
    PROXY_OR_VPN(
            FraudFeatureContract.FLAG_PROXY_OR_VPN,
            ReasonCodeCategory.DEVICE_AND_NETWORK,
            "Proxy or VPN",
            "Network context indicated proxy or VPN usage."
    ),
    HIGH_VELOCITY(
            FraudFeatureContract.FLAG_HIGH_VELOCITY,
            ReasonCodeCategory.VELOCITY,
            "High velocity",
            "Recent transaction frequency contributed to the score."
    ),
    MERCHANT_CONCENTRATION(
            FraudFeatureContract.FLAG_MERCHANT_CONCENTRATION,
            ReasonCodeCategory.MERCHANT,
            "Merchant concentration",
            "Recent merchant activity was concentrated enough to contribute to the score."
    ),
    HIGH_AMOUNT_ACTIVITY(
            FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY,
            ReasonCodeCategory.AMOUNT,
            "High amount activity",
            "Recent amount activity contributed to the score."
    ),
    RAPID_PLN_20K_BURST(
            FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST,
            ReasonCodeCategory.VELOCITY,
            "Rapid PLN 20K burst",
            "A bounded rapid-transfer amount pattern contributed to the score."
    ),
    RECENT_TRANSACTION_SPIKE(
            "RECENT_TRANSACTION_SPIKE",
            ReasonCodeCategory.VELOCITY,
            "Recent transaction spike",
            "Recent transaction count exceeded the scoring threshold."
    ),
    TRANSACTION_VELOCITY(
            "TRANSACTION_VELOCITY",
            ReasonCodeCategory.VELOCITY,
            "Transaction velocity",
            "Transaction velocity per minute contributed to the score."
    ),
    HIGH_TRANSACTION_AMOUNT(
            "HIGH_TRANSACTION_AMOUNT",
            ReasonCodeCategory.AMOUNT,
            "High transaction amount",
            "Single transaction amount crossed a diagnostic threshold."
    ),
    RECENT_AMOUNT_ACCUMULATION(
            "RECENT_AMOUNT_ACCUMULATION",
            ReasonCodeCategory.AMOUNT,
            "Recent amount accumulation",
            "Recent accumulated amount contributed to the score."
    ),
    RAPID_TRANSFER_FRAUD_CASE(
            "RAPID_TRANSFER_FRAUD_CASE",
            ReasonCodeCategory.VELOCITY,
            "Rapid transfer case signal",
            "Rapid-transfer case candidate signal contributed to the score."
    ),
    ML_MODEL_UNAVAILABLE(
            "ML_MODEL_UNAVAILABLE",
            ReasonCodeCategory.MODEL_RUNTIME,
            "ML model unavailable",
            "ML model runtime was unavailable; fallback scoring handled the request."
    ),
    LOW_MODEL_RISK(
            "LOW_MODEL_RISK",
            ReasonCodeCategory.MODEL_RUNTIME,
            "Low model risk",
            "Model output indicated a low scoring signal."
    ),
    MODEL_HIGH_RISK(
            "MODEL_HIGH_RISK",
            ReasonCodeCategory.MODEL_RUNTIME,
            "High model risk",
            "Model output indicated a high scoring signal."
    );

    private static final Map<String, ReasonCode> CANONICAL_BY_WIRE_VALUE = canonicalWireValues();
    private static final Map<String, ReasonCode> LEGACY_ALIASES = legacyAliases();

    private final String wireValue;
    private final ReasonCodeCategory category;
    private final String title;
    private final String description;

    ReasonCode(String wireValue, ReasonCodeCategory category, String title, String description) {
        this.wireValue = wireValue;
        this.category = category;
        this.title = title;
        this.description = description;
    }

    public String wireValue() {
        return wireValue;
    }

    public ReasonCodeCategory category() {
        return category;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public static Optional<ReasonCode> known(String rawValue) {
        ReasonCodeParseResult result = parseLegacy(rawValue);
        return result.supported() && result.reasonCode() != UNKNOWN ? Optional.of(result.reasonCode()) : Optional.empty();
    }

    public static ReasonCodeParseResult parseLegacy(String rawValue) {
        if (rawValue == null) {
            return new ReasonCodeParseResult(UNKNOWN, ReasonCodeParseStatus.NULL_ITEM, null);
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return new ReasonCodeParseResult(UNKNOWN, ReasonCodeParseStatus.BLANK, rawValue);
        }
        ReasonCode canonical = CANONICAL_BY_WIRE_VALUE.get(normalize(trimmed));
        if (canonical != null) {
            return new ReasonCodeParseResult(canonical, ReasonCodeParseStatus.KNOWN, rawValue);
        }
        ReasonCode legacy = LEGACY_ALIASES.get(normalize(trimmed));
        if (legacy != null) {
            return new ReasonCodeParseResult(legacy, ReasonCodeParseStatus.LEGACY_MAPPED, rawValue);
        }
        return new ReasonCodeParseResult(UNKNOWN, ReasonCodeParseStatus.UNSUPPORTED, rawValue);
    }

    public static List<ReasonCodeParseResult> parseLegacyList(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        List<ReasonCodeParseResult> results = new ArrayList<>();
        for (String rawValue : rawValues) {
            results.add(parseLegacy(rawValue));
        }
        return List.copyOf(results);
    }

    public static List<String> wireValues(List<ReasonCodeParseResult> parsedReasonCodes) {
        if (parsedReasonCodes == null || parsedReasonCodes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (ReasonCodeParseResult result : parsedReasonCodes) {
            if (result != null) {
                values.add(result.wireValue());
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, ReasonCode> canonicalWireValues() {
        Map<String, ReasonCode> values = new LinkedHashMap<>();
        for (ReasonCode reasonCode : values()) {
            ReasonCode previous = values.put(normalize(reasonCode.wireValue), reasonCode);
            if (previous != null) {
                throw new IllegalStateException("Duplicate reason-code wire value: " + reasonCode.wireValue);
            }
        }
        return Map.copyOf(values);
    }

    private static Map<String, ReasonCode> legacyAliases() {
        Map<String, ReasonCode> aliases = new LinkedHashMap<>();
        aliases.put(normalize("HIGH_AMOUNT"), HIGH_TRANSACTION_AMOUNT);
        aliases.put(normalize(FraudFeatureContract.COUNTRY_MISMATCH), COUNTRY_MISMATCH);
        aliases.put(normalize(FraudFeatureContract.DEVICE_NOVELTY), DEVICE_NOVELTY);
        aliases.put(normalize(FraudFeatureContract.PROXY_OR_VPN_DETECTED), PROXY_OR_VPN);
        aliases.put(normalize(FraudFeatureContract.RAPID_TRANSFER_BURST), RAPID_PLN_20K_BURST);
        aliases.put(normalize(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE), RAPID_TRANSFER_FRAUD_CASE);
        aliases.put(normalize("rapidTransferFraudCase"), RAPID_TRANSFER_FRAUD_CASE);
        return Map.copyOf(aliases);
    }

    private static String normalize(String value) {
        return value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }
}
