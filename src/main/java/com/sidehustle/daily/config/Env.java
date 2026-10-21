package com.sidehustle.daily.config;

import com.sidehustle.daily.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Secrets live in environment variables. A local {@code .env} file is loaded as fallback
 * (never committed). System environment variables always win.
 */
public final class Env {

    public static final String REDDIT_CLIENT_ID = "REDDIT_CLIENT_ID";
    public static final String REDDIT_CLIENT_SECRET = "REDDIT_CLIENT_SECRET";
    public static final String REDDIT_USER_AGENT = "REDDIT_USER_AGENT";
    public static final String LLM_API_KEY = "LLM_API_KEY";
    public static final String LLM_BASE_URL = "LLM_BASE_URL";
    public static final String LLM_MODEL = "LLM_MODEL";
    public static final String V2EX_TOKEN = "V2EX_TOKEN";
    public static final String GITHUB_TOKEN = "GITHUB_TOKEN";
    public static final String STACKEXCHANGE_KEY = "STACKEXCHANGE_KEY";
    public static final String FEISHU_WEBHOOK = "FEISHU_WEBHOOK";
    public static final String HTTPS_PROXY = "HTTPS_PROXY";

    private static final Map<String, String> DOTENV = loadDotEnv();

    private Env() {}

    public static String get(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) v = DOTENV.get(name);
        if (v == null && HTTPS_PROXY.equals(name)) {
            v = System.getenv("https_proxy");
            if (v == null || v.isBlank()) v = DOTENV.get("https_proxy");
        }
        return v == null ? "" : v.trim();
    }

    public static boolean has(String name) {
        return !get(name).isEmpty();
    }

    private static Map<String, String> loadDotEnv() {
        Path p = Path.of(".env");
        if (!Files.exists(p)) return Map.of();
        Map<String, String> m = new HashMap<>();
        try {
            for (String line : Files.readAllLines(p)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                String k = s.substring(0, eq).strip();
                String v = s.substring(eq + 1).strip();
                if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
                    v = v.substring(1, v.length() - 1);
                }
                m.put(k, v);
            }
            Log.info("loaded %d keys from .env", m.size());
        } catch (IOException e) {
            Log.warn("failed to read .env: %s", e.getMessage());
        }
        return m;
    }
}
