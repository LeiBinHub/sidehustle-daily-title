package com.sidehustle.daily.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Mirrors config.json. Secrets are never stored here; they come from environment variables (see Env). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    public String dbPath = "data/sidehustle.db";
    public String reportDir = "reports";
    public int lookbackDays = 7;
    public int recurrenceWindowDays = 14;
    public int recurrenceThreshold = 3;
    public int maxPostsToClassifyPerRun = 300;
    public int minScoreToReport = 3;

    public Reddit reddit = new Reddit();
    public HackerNews hackerNews = new HackerNews();
    public V2ex v2ex = new V2ex();
    public Freelancer freelancer = new Freelancer();
    public Github github = new Github();
    public StackExchange stackexchange = new StackExchange();
    public Bluesky bluesky = new Bluesky();
    public Llm llm = new Llm();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Reddit {
        public boolean enabled = true;
        public String timeFilter = "week";
        public int limitPerQuery = 50;
        public boolean includeNewListing = true;
        public long requestIntervalMs = 700;
        public List<String> subreddits = List.of();
        public List<String> keywords = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HackerNews {
        public boolean enabled = true;
        public int hitsPerQuery = 50;
        public List<String> keywords = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class V2ex {
        public boolean enabled = true;
        public int pagesPerNode = 2;
        public List<String> nodes = List.of();
        public List<String> keywords = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Freelancer {
        public boolean enabled = true;
        public int limitPerQuery = 20;
        public List<String> keywords = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Github {
        public boolean enabled = true;
        public int perPage = 30;
        public List<String> queries = List.of();
    }

    /** Stack Exchange sites. An empty keyword list means "recent questions on this site". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StackExchange {
        public boolean enabled = true;
        public int pageSize = 30;
        public List<Site> sites = List.of();

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Site {
            public String site = "";
            public List<String> keywords = List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bluesky {
        public boolean enabled = true;
        public int limitPerQuery = 25;
        public List<String> keywords = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Llm {
        public String baseUrl = "https://api.deepseek.com";
        public String model = "deepseek-chat";
        public double temperature = 0.1;
        public int maxBodyChars = 3000;
        public long requestIntervalMs = 200;
    }

    public static AppConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("config not found: " + path.toAbsolutePath());
        }
        ObjectMapper mapper = new ObjectMapper();
        try (var in = Files.newInputStream(path)) {
            return mapper.readValue(in, AppConfig.class);
        }
    }
}
