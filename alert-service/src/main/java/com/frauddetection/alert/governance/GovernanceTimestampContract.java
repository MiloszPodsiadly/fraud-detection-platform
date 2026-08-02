package com.frauddetection.alert.governance;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GovernanceTimestampContract {

    private static final Pattern CANONICAL_UTC_TIMESTAMP = Pattern.compile(
            "^(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})T"
                    + "(?<hour>\\d{2}):(?<minute>\\d{2}):(?<second>\\d{2})(?:\\.(?<fraction>\\d{1,9}))?Z$"
    );

    private GovernanceTimestampContract() {
    }

    public static Instant parse(String value, String field) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must be a canonical UTC timestamp");
        }
        Matcher matcher = CANONICAL_UTC_TIMESTAMP.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(field + " must be a canonical UTC timestamp");
        }
        int year = integer(matcher.group("year"));
        int month = integer(matcher.group("month"));
        int day = integer(matcher.group("day"));
        int hour = integer(matcher.group("hour"));
        int minute = integer(matcher.group("minute"));
        int second = integer(matcher.group("second"));
        if (year < 1 || hour > 23 || minute > 59 || second > 59) {
            throw new IllegalArgumentException(field + " must be a canonical UTC timestamp");
        }
        try {
            LocalDate date = LocalDate.of(year, month, day);
            LocalTime time = LocalTime.of(hour, minute, second, nanos(matcher.group("fraction")));
            return LocalDateTime.of(date, time).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(field + " must be a canonical UTC timestamp", exception);
        }
    }

    private static int integer(String value) {
        return Integer.parseInt(value);
    }

    private static int nanos(String fraction) {
        if (fraction == null) {
            return 0;
        }
        return Integer.parseInt((fraction + "000000000").substring(0, 9));
    }
}
