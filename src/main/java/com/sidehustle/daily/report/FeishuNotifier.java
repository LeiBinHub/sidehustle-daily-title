package com.sidehustle.daily.report;

import com.sidehustle.daily.config.Env;
import com.sidehustle.daily.http.Http;
import com.sidehustle.daily.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpResponse;

/** Optional Feishu custom-bot webhook. Sends the report as a text message (truncated). */
public final class FeishuNotifier {

    private FeishuNotifier() {}

    public static void sendIfConfigured(Path report) {
        if (!Env.has(Env.FEISHU_WEBHOOK) || report == null || !Files.exists(report)) return;
        try {
            String md = Files.readString(report, StandardCharsets.UTF_8);
            if (md.length() > 3500) md = md.substring(0, 3500) + "\n\n…（报告已截断，完整版见 " + report + "）";
            String json = "{\"msg_type\":\"text\",\"content\":{\"text\":"
                    + quote(md)
                    + "}}";
            HttpResponse<String> resp = Http.postJson(Env.get(Env.FEISHU_WEBHOOK), java.util.Map.of(), json);
            if (resp.statusCode() >= 300) {
                Log.warn("feishu webhook HTTP %d %s", resp.statusCode(), resp.body());
            } else {
                Log.info("feishu webhook sent");
            }
        } catch (Exception e) {
            Log.warn("feishu webhook failed: %s", e.getMessage());
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
