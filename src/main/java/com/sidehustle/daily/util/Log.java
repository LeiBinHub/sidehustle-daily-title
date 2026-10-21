package com.sidehustle.daily.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Tiny stdout logger; enough for a cron job. */
public final class Log {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {}

    public static void info(String fmt, Object... args) {
        System.out.println(ts() + " INFO  " + String.format(fmt, args));
    }

    public static void warn(String fmt, Object... args) {
        System.out.println(ts() + " WARN  " + String.format(fmt, args));
    }

    public static void error(String fmt, Object... args) {
        System.err.println(ts() + " ERROR " + String.format(fmt, args));
    }

    private static String ts() {
        return LocalTime.now().format(F);
    }
}
