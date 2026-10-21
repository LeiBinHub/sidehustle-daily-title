package com.sidehustle.daily.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidehustle.daily.config.AppConfig;
import com.sidehustle.daily.config.Env;
import com.sidehustle.daily.http.Http;
import com.sidehustle.daily.model.Post;
import com.sidehustle.daily.util.Log;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reddit Data API via application-only OAuth (client_credentials).
 * Free tier: 100 QPM per OAuth client id, averaged over 10 minutes. We pace requests with a fixed interval
 * and additionally honour x-ratelimit-remaining / x-ratelimit-reset headers.
 *
 * For each subreddit we run (a) one search per keyword restricted to that subreddit, sorted by new,
 * and (b) optionally the /new listing filtered locally by keywords.
 */
public final class RedditSource implements Source {

    private static final String TOKEN_URL = "https://www.reddit.com/api/v1/access_token";
    private static final String API = "https://oauth.reddit.com";

    private final AppConfig.Reddit cfg;
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;

    public RedditSource(AppConfig.Reddit cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() { return "reddit"; }

    @Override
    public boolean isReady() {
        return cfg.enabled
                && Env.has(Env.REDDIT_CLIENT_ID)
                && Env.has(Env.REDDIT_CLIENT_SECRET)
                && Env.has(Env.REDDIT_USER_AGENT);
    }

    @Override
    public List<Post> fetch(long sinceEpoch) throws Exception {
        authenticate();
        Map<String, Post> out = new LinkedHashMap<>();
        int requests = 0;

        for (String sub : cfg.subreddits) {
            for (String kw : cfg.keywords) {
                String url = API + "/r/" + sub + "/search?q=" + enc("\"" + kw + "\"")
                        + "&restrict_sr=1&sort=new&t=" + cfg.timeFilter
                        + "&limit=" + cfg.limitPerQuery + "&raw_json=1";
                requests++;
                JsonNode listing = getJson(url);
                if (listing != null) collect(listing, sub, kw, sinceEpoch, out);
                pace();
            }
            if (cfg.includeNewListing) {
                String url = API + "/r/" + sub + "/new?limit=100&raw_json=1";
                requests++;
                JsonNode listing = getJson(url);
                if (listing != null) collectFiltered(listing, sub, sinceEpoch, out);
                pace();
            }
            Log.info("reddit r/%s done, %d posts so far (%d requests)", sub, out.size(), requests);
        }
        return new ArrayList<>(out.values());
    }

    private void collect(JsonNode listing, String sub, String keyword, long sinceEpoch, Map<String, Post> out) {
        for (JsonNode child : listing.path("data").path("children")) {
            Post p = toPost(child.path("data"), sub, keyword);
            if (p == null || p.createdAt() < sinceEpoch) continue;
            out.putIfAbsent(p.key(), p);
        }
    }

    private void collectFiltered(JsonNode listing, String sub, long sinceEpoch, Map<String, Post> out) {
        for (JsonNode child : listing.path("data").path("children")) {
            JsonNode d = child.path("data");
            String text = (d.path("title").asText("") + "\n" + d.path("selftext").asText("")).toLowerCase();
            String hit = null;
            for (String kw : cfg.keywords) {
                if (text.contains(kw.toLowerCase())) { hit = kw; break; }
            }
            if (hit == null) continue;
            Post p = toPost(d, sub, "new-listing:" + hit);
            if (p == null || p.createdAt() < sinceEpoch) continue;
            out.putIfAbsent(p.key(), p);
        }
    }

    private Post toPost(JsonNode d, String sub, String keyword) {
        String id = d.path("id").asText("");
        if (id.isEmpty()) return null;
        String permalink = d.path("permalink").asText("");
        return new Post(
                "reddit",
                id,
                d.path("title").asText(""),
                d.path("selftext").asText(""),
                permalink.isEmpty() ? d.path("url").asText("") : "https://www.reddit.com" + permalink,
                d.path("subreddit").asText(sub),
                d.path("author").asText(""),
                (long) d.path("created_utc").asDouble(0),
                d.path("score").asInt(0),
                d.path("num_comments").asInt(0),
                keyword);
    }

    private void authenticate() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (Env.get(Env.REDDIT_CLIENT_ID) + ":" + Env.get(Env.REDDIT_CLIENT_SECRET)).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resp = Http.postForm(TOKEN_URL,
                Map.of("Authorization", "Basic " + basic, "User-Agent", Env.get(Env.REDDIT_USER_AGENT)),
                "grant_type=client_credentials");
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("reddit token request failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        JsonNode node = mapper.readTree(resp.body());
        token = node.path("access_token").asText("");
        if (token.isEmpty()) throw new IllegalStateException("reddit token missing in response: " + resp.body());
    }

    private JsonNode getJson(String url) throws Exception {
        HttpResponse<String> resp = Http.get(url, Map.of(
                "Authorization", "Bearer " + token,
                "User-Agent", Env.get(Env.REDDIT_USER_AGENT)));
        honourRateLimit(resp);
        if (resp.statusCode() == 401) {
            authenticate();
            resp = Http.get(url, Map.of("Authorization", "Bearer " + token, "User-Agent", Env.get(Env.REDDIT_USER_AGENT)));
        }
        if (resp.statusCode() != 200) {
            Log.warn("reddit GET %s -> HTTP %d", url, resp.statusCode());
            return null;
        }
        return mapper.readTree(resp.body());
    }

    private void honourRateLimit(HttpResponse<String> resp) throws InterruptedException {
        double remaining = header(resp, "x-ratelimit-remaining", 100);
        double reset = header(resp, "x-ratelimit-reset", 0);
        if (remaining < 3 && reset > 0) {
            long ms = (long) ((reset + 1) * 1000);
            Log.warn("reddit rate limit nearly exhausted, sleeping %d ms", ms);
            Thread.sleep(ms);
        }
    }

    private static double header(HttpResponse<String> resp, String name, double dflt) {
        return resp.headers().firstValue(name).map(v -> {
            try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return dflt; }
        }).orElse(dflt);
    }

    private void pace() throws InterruptedException {
        if (cfg.requestIntervalMs > 0) Thread.sleep(cfg.requestIntervalMs);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
