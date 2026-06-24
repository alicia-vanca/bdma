package com.app.common.utils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralizes local date and timestamp formatting for application code.
 */
public final class DateTimeUtil {

    private static final Logger log = LoggerFactory.getLogger(DateTimeUtil.class);
    private static final DateTimeFormatter SQLITE_DATE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DISPLAY_DATE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("HH:mm dd-MM-yyyy");
    private static final Clock LOCAL_CLOCK = Clock.systemDefaultZone();

    private DateTimeUtil() {
    }

    /**
     * Returns the current date using the application local clock.
     *
     * @return current local date
     */
    public static LocalDate currentLocalDate() {
        return LocalDate.now(LOCAL_CLOCK);
    }

    /**
     * Formats the current local date with the provided formatter.
     *
     * @param formatter date formatter
     * @return formatted current local date
     */
    public static String currentLocalDate(DateTimeFormatter formatter) {
        return currentLocalDate().format(formatter);
    }

    /**
     * Formats the current local timestamp for SQLite text columns.
     *
     * @return timestamp in yyyy-MM-dd HH:mm:ss format
     */
    public static String currentSqliteDateTime() {
        return LocalDateTime.now(LOCAL_CLOCK).format(SQLITE_DATE_TIME_FORMATTER);
    }

    /**
     * Checks whether two dates belong to the same ISO week-based year and week.
     *
     * @param first  first local date
     * @param second second local date
     * @return true when both dates are in the same ISO week
     */
    public static boolean isSameIsoWeek(LocalDate first, LocalDate second) {
        WeekFields weekFields = WeekFields.ISO;
        return first.get(weekFields.weekOfWeekBasedYear()) == second.get(weekFields.weekOfWeekBasedYear())
                && first.get(weekFields.weekBasedYear()) == second.get(weekFields.weekBasedYear());
    }

    /**
     * Converts SQLite local timestamps to the short display format used by UI text.
     *
     * @param value        timestamp in yyyy-MM-dd HH:mm:ss format
     * @param fallbackText text returned when value is null or blank
     * @return formatted timestamp, fallback text, or raw value when parsing fails
     */
    public static String formatSqliteDateTimeForDisplay(String value, String fallbackText) {
        if (value == null || value.isBlank()) {
            return fallbackText;
        }
        try {
            return LocalDateTime.parse(value, SQLITE_DATE_TIME_FORMATTER).format(DISPLAY_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ex) {
            log.debug("Unexpected SQLite timestamp format: {}", value, ex);
            return value;
        }
    }
}
