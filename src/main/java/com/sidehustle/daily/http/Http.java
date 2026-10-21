package com.sidehustle.daily.http;

import com.sidehustle.daily.config.Env;
import com.sidehustle.daily.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Minimal HttpClient wrapper with retry/backoff on 429 and 5xx, optional HTTPS_PROXY. */
public final class Http {

    private static final HttpClient CLIENT = buildClient();
    private static final long[] BACKOFF_MS = {2_000, 5_000, 10_000};

    private Http() {}

    private static HttpClient buildClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = Env.get(Env.HTTPS_PROXY);
        if (!proxy.isEmpty()) {
            URI p = URI.create(proxy);
            int port = p.getPort() > 0 ? p.getPort() : ("https".equalsIgnoreCase(p.getScheme()) ? 443 : 80);
            b.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), port)));
            Log.info("HTTP proxy %s", proxy);
        }
        return b.build();
    }

    public static HttpResponse<String> get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        return send(builder(url, headers).GET().build());
    }

    public static HttpResponse<String> postForm(String url, Map<String, String> headers, String form) throws IOException, InterruptedException {
        HttpRequest.Builder b = builder(url, headers)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        return send(b.build());
    }

    public static HttpResponse<String> postJson(String url, Map<String, String> headers, String json) throws IOException, InterruptedException {
        HttpRequest.Builder b = builder(url, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        return send(b.build());
    }

    private static HttpRequest.Builder builder(String url, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (headers != null) headers.forEach(b::header);
        return b;
    }

    private static HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            try {
                HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();
                if (code == 429 || code >= 500) {
                    if (attempt == BACKOFF_MS.length) return resp;
                    long wait = BACKOFF_MS[attempt];
                    String ra = resp.headers().firstValue("retry-after").orElse(null);
                    if (ra != null) {
                        try { wait = Math.max(wait, Long.parseLong(ra.trim()) * 1000); } catch (NumberFormatException ignored) {}
                    }
                    Thread.sleep(wait);
                    continue;
                }
                return resp;
            } catch (IOException e) {
                last = e;
                if (attempt == BACKOFF_MS.length) break;
                Thread.sleep(BACKOFF_MS[attempt]);
            }
        }
        throw last != null ? last : new IOException("request failed: " + req.uri());
    }
}
