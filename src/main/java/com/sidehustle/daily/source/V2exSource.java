package com.sidehustle.daily.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidehustle.daily.config.AppConfig;
import com.sidehustle.daily.config.Env;
import com.sidehustle.daily.http.Http;
import com.sidehustle.daily.model.Post;
import com.sidehustle.daily.util.Log;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2EX API 2.0 (Bearer token). Pulls recent topics from selected nodes and keeps those matching
 * any configured keyword. Keyword list may be empty, in which case every topic in the node is kept.
 */
public final class V2exSource implements Source {

    private static final String API = "https://www.v2ex.com/api/v2";

    private final AppConfig.V2ex cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public V2exSource(AppConfig.V2ex cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() { return "v2ex"; }

    @Override
    public boolean isReady() { return cfg.enabled && Env.has(Env.V2EX_TOKEN); }

    @Override
    public List<Post> fetch(long sinceEpoch) throws Exception {
        Map<String, Post> out = new LinkedHashMap<>();
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + Env.get(Env.V2EX_TOKEN),
                "User-Agent", "sidehustle-daily-title/0.1");

        for (String node : cfg.nodes) {
            for (int page = 1; page <= cfg.pagesPerNode; page++) {
                String url = API + "/nodes/" + node + "/topics?p=" + page;
                HttpResponse<String> resp = Http.get(url, headers);
                if (resp.statusCode() != 200) {
                    Log.warn("v2ex GET %s -> HTTP %d", url, resp.statusCode());
                    break;
                }
                JsonNode root = mapper.readTree(resp.body());
                if (!root.path("success").asBoolean(false)) {
                    Log.warn("v2ex API returned success=false for %s: %s", url, root.path("message").asText(""));
                    break;
                }
                boolean anyRecent = false;
                for (JsonNode t : root.path("result")) {
                    long created = t.path("created").asLong(0);
                    if (created < sinceEpoch) continue;
                    anyRecent = true;
                    String title = t.path("title").asText("");
                    String content = t.path("content").asText("");
                    String hit = matchKeyword(title + "\n" + content);
                    if (hit == null) continue;
                    String id = t.path("id").asText("");
                    Post p = new Post(
                            "v2ex",
                            id,
                            title,
                            content,
                            t.path("url").asText("https://www.v2ex.com/t/" + id),
                            t.path("node").path("name").asText(node),
                            t.path("member").path("username").asText(""),
                            created,
                            0,
                            t.path("replies").asInt(0),
                            hit);
                    out.putIfAbsent(p.key(), p);
                }
                if (!anyRecent) break; // pages are newest-first; stop once everything is older than the window
                Thread.sleep(500);
            }
        }
        Log.info("v2ex done, %d posts", out.size());
        return new ArrayList<>(out.values());
    }

    private String matchKeyword(String text) {
        if (cfg.keywords == null || cfg.keywords.isEmpty()) return "node-listing";
        for (String kw : cfg.keywords) {
            if (text.contains(kw)) return kw;
        }
        return null;
    }
}
