package com.frauddetection.alert.feedback.dataset;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class FeedbackDatasetSafety {

    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|s3://|gs://|jdbc:|ftp://)\\S+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(token|secret|apikey|api_key|password|bearer|authorization)[A-Za-z0-9_:=.-]*"
    );
    private static final Set<String> FORBIDDEN_COMPACT_TERMS = Set.of(
            "customerid",
            "correlationid",
            "createdby",
            "notes",
            "rawnotes",
            "rawpayload",
            "rawfeaturevector",
            "rawevidence",
            "rawmlrequest",
            "rawmlresponse",
            "stacktrace",
            "exceptionmessage",
            "token",
            "secret",
            "password",
            "groundtruth",
            "traininglabel",
            "finaldecision",
            "paymentdecision",
            "paymentauthorization"
    );

    private FeedbackDatasetSafety() {
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

    static List<String> copyRequiredMachineCodes(List<String> source, String fieldName, int maxSize) {
        List<String> values = copyMachineCodes(source, fieldName, maxSize);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return values;
    }

    static String optionalSafeIdentifier(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 64 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must be bounded");
        }
        rejectUnsafeValue(value, fieldName);
        if (value.contains("/") || value.contains("\\") || value.contains("://")) {
            throw new IllegalArgumentException(fieldName + " must not contain paths or endpoints");
        }
        return value;
    }

    private static String requireMachineCode(String value, String fieldName) {
        if (value == null || !MACHINE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must use bounded UPPER_SNAKE_CASE");
        }
        rejectUnsafeValue(value, fieldName);
        return value;
    }

    private static void rejectUnsafeValue(String value, String fieldName) {
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (FORBIDDEN_COMPACT_TERMS.stream().anyMatch(compact::contains)
                || EMAIL.matcher(value).find()
                || URL.matcher(value).find()
                || SECRET.matcher(value).find()) {
            throw new IllegalArgumentException(fieldName + " contains forbidden dataset value");
        }
    }
}
