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

/**
 * Freelancer.com public projects API (no auth). Used as a compliant "paid pain" feed
 * after Upwork RSS was retired and Fiverr briefs became private matching.
 */
public final class FreelancerSource implements Source {

    private static final String API = "https://www.freelancer.com/api/projects/0.1/projects/all/";

    private final AppConfig.Freelancer cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public FreelancerSource(AppConfig.Freelancer cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() { return "freelancer"; }

    @Override
    public boolean isReady() { return cfg.enabled; }

    @Override
    public List<Post> fetch(long sinceEpoch) throws Exception {
        Map<String, Post> out = new LinkedHashMap<>();
        for (String kw : cfg.keywords) {
            String url = API + "?limit=" + cfg.limitPerQuery
                    + "&full_description=true"
                    + "&compact=true"
                    + "&query=" + URLEncoder.encode(kw, StandardCharsets.UTF_8);
            HttpResponse<String> resp = Http.get(url, Map.of("User-Agent", "sidehustle-daily-title/0.1"));
            if (resp.statusCode() != 200) {
                Log.warn("freelancer GET %s -> HTTP %d", url, resp.statusCode());
                continue;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode projects = root.path("result").path("projects");
            if (!projects.isArray()) {
                Log.warn("freelancer unexpected payload for keyword '%s'", kw);
                continue;
            }
            for (JsonNode p : projects) {
                long created = p.path("submitdate").asLong(p.path("time_submitted").asLong(0));
                if (created > 0 && created < sinceEpoch) continue;
                String id = p.path("id").asText("");
                if (id.isEmpty()) continue;
                String link = projectUrl(p.path("seo_url").asText(""), id);
                String body = firstNonBlank(p, "description", "preview_description", "excerpt");
                Post post = new Post(
                        "freelancer",
                        id,
                        p.path("title").asText(""),
                        body,
                        link,
                        "projects",
                        p.path("owner_id").asText(""),
                        created,
                        0,
                        p.path("bid_stats").path("bid_count").asInt(0),
                        kw);
                out.putIfAbsent(post.key(), post);
            }
            Thread.sleep(400);
        }
        Log.info("freelancer done, %d posts", out.size());
        return new ArrayList<>(out.values());
    }

    /** Freelancer project pages live under /projects/{category}/{slug}. seo_url omits that prefix. */
    static String projectUrl(String seoUrl, String id) {
        if (seoUrl != null && seoUrl.startsWith("http")) return seoUrl;
        String path = seoUrl == null ? "" : seoUrl.replaceFirst("^/", "");
        if (path.isEmpty()) return "https://www.freelancer.com/projects/" + id;
        if (!path.startsWith("projects/")) path = "projects/" + path;
        return "https://www.freelancer.com/" + path;
    }

    private static String firstNonBlank(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = n.path(f).asText("");
            if (!v.isBlank()) return v;
        }
        return "";
    }
}
