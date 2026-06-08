package com.frauddetection.alert.engineintelligence.dataset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class EngineIntelligenceFeedbackDatasetSafety {

    private static final Pattern EVALUATION_RECORD_ID = Pattern.compile("eval-[a-f0-9]{32}");
    private static final Pattern TRANSACTION_REFERENCE = Pattern.compile("txnref-[a-f0-9]{32}");
    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
    private static final Pattern PAN = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|s3://|gs://|jdbc:|ftp://)\\S+");
    private static final Pattern STACKTRACE = Pattern.compile("(?i)(\\bat\\s+[a-z0-9_.$]+\\([^)]*\\.java:\\d+\\)|exception|stacktrace)");
    private static final Pattern RAW_IDENTIFIER = Pattern.compile(
            "(?i)(customer|account|card|device|merchant|submittedBy|analyst|correlation|idempotency|requestPayloadHash)[_-]?[a-z0-9:-]+"
    );
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(token|secret|apikey|api_key|password|bearer|authorization)[A-Za-z0-9_:=.-]*"
    );
    private static final Set<String> FORBIDDEN_COMPACT_TERMS = Set.of(
            "customerid",
            "accountid",
            "cardid",
            "deviceid",
            "merchantid",
            "pan",
            "iban",
            "email",
            "phone",
            "submittedby",
            "analystid",
            "correlationid",
            "idempotencykey",
            "requestpayloadhash",
            "rawpayload",
            "rawfeaturevector",
            "rawfeaturesnapshot",
            "rawevidence",
            "rawcontribution",
            "rawmlrequest",
            "rawmlresponse",
            "stacktrace",
            "exceptionmessage",
            "token",
            "secret",
            "endpoint",
            "metadata",
            "groundtruth",
            "traininglabel",
            "modeltraininglabel",
            "finaldecision",
            "paymentauthorization"
    );

    private EngineIntelligenceFeedbackDatasetSafety() {
    }

    static String evaluationRecordId(String feedbackId) {
        requireSourceIdentifier(feedbackId, "feedbackId");
        return "eval-" + sha256(feedbackId).substring(0, 32);
    }

    static String transactionReference(String transactionId) {
        requireSourceIdentifier(transactionId, "transactionId");
        return "txnref-" + sha256(transactionId).substring(0, 32);
    }

    static String requireEvaluationRecordId(String value) {
        if (value == null || !EVALUATION_RECORD_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("evaluationRecordId must be a bounded pseudonymous evaluation id");
        }
        return value;
    }

    static String requireTransactionReference(String value) {
        if (value == null || !TRANSACTION_REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("transactionReference must be a bounded pseudonymous transaction reference");
        }
        return value;
    }

    static List<String> copyMachineCodes(List<String> source, String fieldName, int maxSize) {
        if (source == null) {
            return List.of();
        }
        if (source.size() > maxSize) {
            throw new IllegalArgumentException(fieldName + " exceeds max size");
        }
        return source.stream()
                .map(value -> requireMachineCode(value, fieldName))
                .toList();
    }

    static String requireMachineCode(String value, String fieldName) {
        if (value == null || !MACHINE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must use bounded UPPER_SNAKE_CASE");
        }
        rejectUnsafeValue(value, fieldName);
        return value;
    }

    static String optionalSafeIdentifier(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 64 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must be a bounded safe identifier");
        }
        rejectUnsafeValue(value, fieldName);
        if (value.contains("/") || value.contains("\\") || value.contains("://")) {
            throw new IllegalArgumentException(fieldName + " must not contain paths or endpoints");
        }
        return value;
    }

    static void rejectUnsafeValue(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " is required");
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (FORBIDDEN_COMPACT_TERMS.stream().anyMatch(compact::contains)
                || EMAIL.matcher(value).find()
                || IBAN.matcher(value.replace(" ", "")).find()
                || PAN.matcher(value).find()
                || URL.matcher(value).find()
                || STACKTRACE.matcher(value).find()
                || RAW_IDENTIFIER.matcher(value).find()
                || SECRET.matcher(value).find()) {
            throw new IllegalArgumentException(fieldName + " contains forbidden dataset export value");
        }
    }

    private static void requireSourceIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
