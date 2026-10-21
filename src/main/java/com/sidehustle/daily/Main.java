package com.sidehustle.daily;

/**
 * Usage:
 *   java -jar sidehustle-daily-title.jar [run|fetch|classify|report] [--config config.json]
 *
 *   run       fetch + classify + report (default)
 *   fetch     pull new posts from all ready sources into SQLite
 *   classify  run the LLM over every unclassified post
 *   report    regenerate today's report (recurrence table + opportunities classified in the last 24h)
 */
public final class Main {

    public static void main(String[] args) {
        DailyApplication.main(args);
    }
}
