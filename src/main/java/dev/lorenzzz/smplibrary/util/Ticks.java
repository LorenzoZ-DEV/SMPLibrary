package dev.lorenzzz.smplibrary.util;

import java.util.Locale;

/** Parses the {@code 30s} / {@code 5m} / {@code 1h} / {@code 2d} periods used across config.yml. */
public final class Ticks {

    public static final long SECOND = 20L;
    public static final long MINUTE = 60 * SECOND;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;

    private Ticks() {}

    /**
     * {@code "5m"} -> 6000 ticks. A bare number is read as seconds; a blank, malformed
     * or non-positive value falls back to {@code fallbackTicks}.
     */
    public static long parse(String period, long fallbackTicks) {
        if (period == null || period.isBlank()) return fallbackTicks;

        String trimmed = period.trim().toLowerCase(Locale.ROOT);
        char unit = trimmed.charAt(trimmed.length() - 1);
        boolean suffixed = Character.isLetter(unit);
        String number = suffixed ? trimmed.substring(0, trimmed.length() - 1) : trimmed;

        long amount;
        try {
            amount = Long.parseLong(number.trim());
        } catch (NumberFormatException e) {
            return fallbackTicks;
        }
        if (amount <= 0) return fallbackTicks;

        long ticks = switch (suffixed ? unit : 's') {
            case 's' -> amount * SECOND;
            case 'm' -> amount * MINUTE;
            case 'h' -> amount * HOUR;
            case 'd' -> amount * DAY;
            default -> amount * SECOND;
        };
        return ticks <= 0 ? fallbackTicks : ticks;
    }

    /** {@link #parse} with a floor, so a misconfigured period can never spam the server. */
    public static long parseAtLeast(String period, long fallbackTicks, long minimumTicks) {
        return Math.max(parse(period, fallbackTicks), minimumTicks);
    }

    /** "2d 3h 4m 5s", trimmed to the first non-zero unit; {@code "now"} once expired. */
    public static String countdown(long millisRemaining) {
        long remaining = millisRemaining / 1000L;
        if (remaining <= 0) return "now";

        long days = remaining / 86_400L; remaining %= 86_400L;
        long hours = remaining / 3_600L; remaining %= 3_600L;
        long minutes = remaining / 60L;  remaining %= 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('d');
        if (hours > 0 || !sb.isEmpty()) append(sb, hours, 'h');
        if (minutes > 0 || !sb.isEmpty()) append(sb, minutes, 'm');
        append(sb, remaining, 's');
        return sb.toString();
    }

    private static void append(StringBuilder sb, long value, char unit) {
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(value).append(unit);
    }
}
