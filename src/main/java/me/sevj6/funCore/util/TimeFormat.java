package me.sevj6.funCore.util;

/** Times as text, Bukkit-free so it can be unit-tested. (Whole seconds: {@link Utils#formatTime}.) */
public final class TimeFormat {

    private TimeFormat() {
    }

    /**
     * Milliseconds as {@code m:ss.SSS}, or {@code h:mm:ss.SSS} from an hour: {@code 0:05.250}, {@code 1:30.450},
     * {@code 1:02:03.004}. Negative times are 0.
     */
    public static String withMillis(long millis) {
        millis = Math.max(0, millis);
        long hours = millis / 3_600_000;
        long minutes = millis / 60_000 % 60;
        long seconds = millis / 1000 % 60;
        long rest = millis % 1000;
        return hours > 0
                ? "%d:%02d:%02d.%03d".formatted(hours, minutes, seconds, rest)
                : "%d:%02d.%03d".formatted(minutes, seconds, rest);
    }

    /** Game ticks (20 a second, so 50 ms each) as {@link #withMillis}: 1,809 ticks is {@code 1:30.450}. */
    public static String ticksWithMillis(long ticks) {
        return withMillis(Math.clamp(ticks, 0, Long.MAX_VALUE / 50) * 50);
    }
}
