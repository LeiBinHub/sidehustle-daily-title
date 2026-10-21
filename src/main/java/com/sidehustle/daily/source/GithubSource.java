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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub Issues search. Feature-request / "I wish" issues on public repos are a cheap demand signal.
 * Anonymous rate limit is 10 req/min; a token (GITHUB_TOKEN) raises it to 30.
 */
public final class GithubSource implements Source {

    private static final String API = "https://api.github.com/search/issues";

    private final AppConfig.Github cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public GithubSource(AppConfig.Github cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() { return "github"; }

    @Override
    public boolean isReady() { return cfg.enabled; }

    @Override
    public List<Post> fetch(long sinceEpoch) throws Exception {
        Map<String, Post> out = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "sidehustle-daily-title/0.1");
        headers.put("Accept", "application/vnd.github+json");
        if (Env.has(Env.GITHUB_TOKEN)) {
            headers.put("Authorization", "Bearer " + Env.get(Env.GITHUB_TOKEN));
        }

        for (String q : cfg.queries) {
            String url = API + "?q=" + URLEncoder.encode(q + " is:issue is:open", StandardCharsets.UTF_8)
                    + "&sort=updated&order=desc&per_page=" + cfg.perPage;
            HttpResponse<String> resp = Http.get(url, headers);
            if (resp.statusCode() != 200) {
                Log.warn("github GET search -> HTTP %d %s", resp.statusCode(), abbreviate(resp.body()));
                continue;
            }
            JsonNode root = mapper.readTree(resp.body());
            for (JsonNode item : root.path("items")) {
                long created = parseIso(item.path("created_at").asText(""));
                if (created > 0 && created < sinceEpoch) continue;
                String id = item.path("id").asText("");
                if (id.isEmpty()) continue;
                String repo = item.path("repository_url").asText("");
                int slash = repo.lastIndexOf('/');
                String community = slash >= 0 ? repo.substring(repo.lastIndexOf('/', slash - 1) + 1) : "github";
                Post p = new Post(
                        "github",
                        id,
                        item.path("title").asText(""),
                        item.path("body").asText(""),
                        item.path("html_url").asText(""),
                        community,
                        item.path("user").path("login").asText(""),
                        created,
                        item.path("reactions").path("total_count").asInt(0),
                        item.path("comments").asInt(0),
                        q);
                out.putIfAbsent(p.key(), p);
            }
            Thread.sleep(800);
        }
        Log.info("github done, %d posts", out.size());
        return new ArrayList<>(out.values());
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
