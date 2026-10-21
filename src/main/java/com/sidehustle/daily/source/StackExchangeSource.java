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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stack Exchange public API. No key is required (about 300 calls/day per IP).
 * An optional STACKEXCHANGE_KEY raises that quota; OAuth is not used.
 */
public final class StackExchangeSource implements Source {

    private static final String API = "https://api.stackexchange.com/2.3/";

    private final AppConfig.StackExchange cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public StackExchangeSource(AppConfig.StackExchange cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() { return "stackexchange"; }

    @Override
    public boolean isReady() { return cfg.enabled; }

    @Override
    public List<Post> fetch(long sinceEpoch) throws Exception {
        Map<String, Post> out = new LinkedHashMap<>();
        int pageSize = Math.min(100, Math.max(1, cfg.pageSize));
        for (AppConfig.StackExchange.Site site : cfg.sites) {
            if (site.site == null || site.site.isBlank()) continue;
            List<String> keywords = site.keywords == null || site.keywords.isEmpty()
                    ? List.of("")
                    : site.keywords;
            for (String keyword : keywords) {
                String path = keyword.isBlank() ? "questions" : "search/advanced";
                String url = API + path
                        + "?site=" + URLEncoder.encode(site.site, StandardCharsets.UTF_8)
                        + "&pagesize=" + pageSize
                        + "&order=desc&sort=creation"
                        + "&fromdate=" + sinceEpoch
                        + "&filter=withbody";
                if (!keyword.isBlank()) {
                    url += "&q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
                }
                if (Env.has(Env.STACKEXCHANGE_KEY)) {
                    url += "&key=" + URLEncoder.encode(Env.get(Env.STACKEXCHANGE_KEY), StandardCharsets.UTF_8);
                }
                HttpResponse<String> resp = Http.get(url, Map.of(
                        "User-Agent", "sidehustle-daily-title/0.1",
                        "Accept", "application/json"));
                if (resp.statusCode() != 200) {
                    Log.warn("stackexchange %s -> HTTP %d %s", site.site, resp.statusCode(), abbreviate(resp.body()));
                    continue;
                }
                JsonNode root = mapper.readTree(resp.body());
                if (root.has("error_id")) {
                    Log.warn("stackexchange %s error %s: %s", site.site,
                            root.path("error_name").asText(""), root.path("error_message").asText(""));
                    continue;
                }
                for (JsonNode item : root.path("items")) {
                    if (!item.path("closed_date").isMissingNode() && !item.path("closed_date").isNull()) continue;
                    String id = item.path("question_id").asText("");
                    if (id.isEmpty()) continue;
                    String matched = keyword.isBlank() ? site.site : keyword;
                    Post post = new Post(
                            "stackexchange",
                            site.site + ":" + id,
                            item.path("title").asText(""),
                            plain(item.path("body").asText("")),
                            item.path("link").asText(""),
                            site.site,
                            item.path("owner").path("display_name").asText(""),
                            item.path("creation_date").asLong(0),
                            item.path("score").asInt(0),
                            item.path("answer_count").asInt(0),
                            matched);
                    out.putIfAbsent(post.key(), post);
                }
                int remaining = root.path("quota_remaining").asInt(999);
                int backoff = root.path("backoff").asInt(0);
                if (backoff > 0) Thread.sleep(backoff * 1000L);
                else Thread.sleep(300);
                if (remaining < 8) {
                    Log.warn("stackexchange quota nearly spent (%d left), stopping this source", remaining);
                    Log.info("stackexchange done, %d posts", out.size());
                    return new ArrayList<>(out.values());
                }
            }
        }
        Log.info("stackexchange done, %d posts", out.size());
        return new ArrayList<>(out.values());
    }

    static String plain(String html) {
        if (html == null || html.isBlank()) return "";
        String text = html.replaceAll("(?s)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 180 ? s.substring(0, 180) + "..." : s;
    }
}
