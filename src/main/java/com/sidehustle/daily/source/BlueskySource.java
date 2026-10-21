package com.sidehustle.daily.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidehustle.daily.config.AppConfig;
import com.sidehustle.daily.http.Http;
import com.sidehustle.daily.model.Post;
import com.sidehustle.daily.util.Log;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bluesky public post search. No app registration and no token.
 * Uses api.bsky.app; public.api.bsky.app currently rejects this call from some networks.
 */
public final class BlueskySource implements Source {

    private static final String API = "https://api.bsky.app/xrpc/app.bsky.feed.searchPosts";

    private final AppConfig.Bluesky cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public BlueskySource(AppConfig.Bluesky cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() { return "bluesky"; }

    @Override
    public boolean isReady() { return cfg.enabled; }

    @Override
    public List<Post> fetch(long sinceEpoch) throws Exception {
        Map<String, Post> out = new LinkedHashMap<>();
        int limit = Math.min(100, Math.max(1, cfg.limitPerQuery));
        String since = Instant.ofEpochSecond(sinceEpoch).toString();
        for (String keyword : cfg.keywords) {
            if (keyword == null || keyword.isBlank()) continue;
            String url = API + "?q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&sort=latest&limit=" + limit
                    + "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8);
            HttpResponse<String> resp = Http.get(url, Map.of(
                    "User-Agent", "sidehustle-daily-title/0.1",
                    "Accept", "application/json"));
            if (resp.statusCode() != 200) {
                Log.warn("bluesky GET search -> HTTP %d %s", resp.statusCode(), abbreviate(resp.body()));
                continue;
            }
            JsonNode root = mapper.readTree(resp.body());
            for (JsonNode item : root.path("posts")) {
                JsonNode record = item.path("record");
                String text = record.path("text").asText("");
                if (!containsPhrase(text, keyword)) continue;
                long created = parseIso(record.path("createdAt").asText(""));
                if (created > 0 && created < sinceEpoch) continue;
                String uri = item.path("uri").asText("");
                if (uri.isEmpty()) continue;
                String handle = item.path("author").path("handle").asText("");
                Post post = new Post(
                        "bluesky",
                        uri,
                        firstLine(text),
                        text,
                        postUrl(handle, uri),
                        handle.isEmpty() ? "bluesky" : handle,
                        handle,
                        created,
                        item.path("likeCount").asInt(0),
                        item.path("replyCount").asInt(0),
                        keyword);
                out.putIfAbsent(post.key(), post);
            }
            Thread.sleep(400);
        }
        Log.info("bluesky done, %d posts", out.size());
        return new ArrayList<>(out.values());
    }

    static boolean containsPhrase(String text, String keyword) {
        String phrase = keyword.replace("\"", "").trim().toLowerCase(Locale.ROOT);
        if (phrase.isEmpty() || text == null) return false;
        return text.toLowerCase(Locale.ROOT).contains(phrase);
    }

    static String postUrl(String handle, String uri) {
        int slash = uri.lastIndexOf('/');
        String rkey = slash >= 0 ? uri.substring(slash + 1) : uri;
        String who = handle == null || handle.isBlank() ? "bsky.app" : handle;
        return "https://bsky.app/profile/" + who + "/post/" + rkey;
    }

    private static String firstLine(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > 140 ? flat.substring(0, 140) + "…" : flat;
    }

    private static long parseIso(String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return Instant.parse(iso).getEpochSecond();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 180 ? s.substring(0, 180) + "..." : s;
    }
}
