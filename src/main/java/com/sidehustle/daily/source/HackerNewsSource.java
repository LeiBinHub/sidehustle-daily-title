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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hacker News via the public Algolia search API (no auth required). Stories and Ask HN posts only. */
public final class HackerNewsSource implements Source {

    private static final String API = "https://hn.algolia.com/api/v1/search_by_date";

    private final AppConfig.HackerNews cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public HackerNewsSource(AppConfig.HackerNews cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() { return "hackernews"; }

    @Override
    public boolean isReady() { return cfg.enabled; }

    @Override
    public List<Post> fetch(long sinceEpoch) throws Exception {
        Map<String, Post> out = new LinkedHashMap<>();
        for (String kw : cfg.keywords) {
            String url = API + "?query=" + URLEncoder.encode(kw, StandardCharsets.UTF_8)
                    + "&tags=" + URLEncoder.encode("(story,ask_hn)", StandardCharsets.UTF_8)
                    + "&numericFilters=" + URLEncoder.encode("created_at_i>" + sinceEpoch, StandardCharsets.UTF_8)
                    + "&hitsPerPage=" + cfg.hitsPerQuery;
            HttpResponse<String> resp = Http.get(url, Map.of("User-Agent", "sidehustle-daily-title/0.1"));
            if (resp.statusCode() != 200) {
                Log.warn("hn GET %s -> HTTP %d", url, resp.statusCode());
                continue;
            }
            JsonNode root = mapper.readTree(resp.body());
            for (JsonNode hit : root.path("hits")) {
                String id = hit.path("objectID").asText("");
                if (id.isEmpty()) continue;
                boolean ask = hit.path("_tags").toString().contains("ask_hn");
                Post p = new Post(
                        "hackernews",
                        id,
                        hit.path("title").asText(""),
                        hit.path("story_text").asText(""),
                        "https://news.ycombinator.com/item?id=" + id,
                        ask ? "ask_hn" : "story",
                        hit.path("author").asText(""),
                        hit.path("created_at_i").asLong(0),
                        hit.path("points").asInt(0),
                        hit.path("num_comments").asInt(0),
                        kw);
                out.putIfAbsent(p.key(), p);
            }
            Thread.sleep(300);
        }
        Log.info("hackernews done, %d posts", out.size());
        return new ArrayList<>(out.values());
    }
}
