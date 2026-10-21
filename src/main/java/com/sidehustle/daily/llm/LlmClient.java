package com.sidehustle.daily.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sidehustle.daily.config.AppConfig;
import com.sidehustle.daily.config.Env;
import com.sidehustle.daily.http.Http;
import com.sidehustle.daily.model.KnownDemand;
import com.sidehustle.daily.model.Opportunity;
import com.sidehustle.daily.model.Post;

import java.util.List;
import com.sidehustle.daily.util.Log;

import java.net.http.HttpResponse;
import java.util.Map;

/**
 * OpenAI-compatible chat-completions client. Works with DeepSeek, Qwen (DashScope compatible mode),
 * Moonshot, OpenAI, etc. — anything exposing POST {baseUrl}/chat/completions.
 */
public final class LlmClient {

    private final AppConfig.Llm cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(AppConfig.Llm cfg) {
        this.cfg = cfg;
    }

    public boolean isReady() {
        return Env.has(Env.LLM_API_KEY);
    }

    public Opportunity classify(Post post, List<KnownDemand> known) throws Exception {
        String body = post.body() == null ? "" : post.body();
        if (body.length() > cfg.maxBodyChars) body = body.substring(0, cfg.maxBodyChars) + "\n...(truncated)";

        ObjectNode req = mapper.createObjectNode();
        req.put("temperature", cfg.temperature);
        ObjectNode fmt = req.putObject("response_format");
        fmt.put("type", "json_object");
        ArrayNode messages = req.putArray("messages");
        messages.addObject().put("role", "system").put("content", Prompts.SYSTEM);
        messages.addObject().put("role", "user").put("content",
                Prompts.user(post.source(), post.community(), post.title(), body, post.numComments(), post.score(), known));

        String base = Env.has(Env.LLM_BASE_URL) ? Env.get(Env.LLM_BASE_URL) : cfg.baseUrl;
        String model = Env.has(Env.LLM_MODEL) ? Env.get(Env.LLM_MODEL) : cfg.model;
        req.put("model", model);
        String url = base.replaceAll("/+$", "") + "/chat/completions";
        HttpResponse<String> resp = Http.postJson(url,
                Map.of("Authorization", "Bearer " + Env.get(Env.LLM_API_KEY)),
                mapper.writeValueAsString(req));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("LLM HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        return parse(post, content);
    }

    private Opportunity parse(Post post, String content) {
        String json = stripFences(content);
        try {
            JsonNode n = mapper.readTree(json);
            boolean is = n.path("is_opportunity").asBoolean(false);
            double conf = n.path("confidence").asDouble(0.5);
            if (!is) {
                return Opportunity.notOpportunity(post.source(), post.id(), conf, n.path("summary_zh").asText(""));
            }
            return new Opportunity(
                    post.source(), post.id(), true, conf,
                    normalizeKey(n.path("pain_key").asText("")),
                    n.path("pain_point").asText(""),
                    n.path("audience").asText(""),
                    n.path("existing_tools").asText(""),
                    n.path("paying_evidence").asText(""),
                    clamp(n.path("score").asInt(1), 1, 5),
                    n.path("summary_zh").asText(""));
        } catch (Exception e) {
            Log.warn("LLM returned non-JSON for %s: %s", post.key(), abbreviate(content));
            return Opportunity.notOpportunity(post.source(), post.id(), 0.0, "parse-error");
        }
    }

    static String normalizeKey(String k) {
        String s = k == null ? "" : k.trim().toLowerCase();
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s;
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : t.substring(3);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
