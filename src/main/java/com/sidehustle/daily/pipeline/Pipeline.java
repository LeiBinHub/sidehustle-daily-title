package com.sidehustle.daily.pipeline;

import com.sidehustle.daily.config.AppConfig;
import com.sidehustle.daily.llm.LlmClient;
import com.sidehustle.daily.model.KnownDemand;
import com.sidehustle.daily.model.Opportunity;
import com.sidehustle.daily.model.Post;
import com.sidehustle.daily.report.ReportGenerator;
import com.sidehustle.daily.report.FeishuNotifier;
import com.sidehustle.daily.source.BlueskySource;
import com.sidehustle.daily.source.FreelancerSource;
import com.sidehustle.daily.source.GithubSource;
import com.sidehustle.daily.source.HackerNewsSource;
import com.sidehustle.daily.source.RedditSource;
import com.sidehustle.daily.source.Source;
import com.sidehustle.daily.source.StackExchangeSource;
import com.sidehustle.daily.source.V2exSource;
import com.sidehustle.daily.store.Store;
import com.sidehustle.daily.util.Log;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/** fetch -> dedupe/store -> classify -> report. Each stage is independently runnable. */
public final class Pipeline {

    private final AppConfig cfg;
    private final Store store;
    private final LlmClient llm;
    private final List<Source> sources;

    public Pipeline(AppConfig cfg, Store store) {
        this.cfg = cfg;
        this.store = store;
        this.llm = new LlmClient(cfg.llm);
        this.sources = List.of(
                new RedditSource(cfg.reddit),
                new HackerNewsSource(cfg.hackerNews),
                new V2exSource(cfg.v2ex),
                new FreelancerSource(cfg.freelancer),
                new GithubSource(cfg.github),
                new StackExchangeSource(cfg.stackexchange),
                new BlueskySource(cfg.bluesky));
    }

    public record FetchResult(int fetched, int newPosts) {}

    /** Called as each source finishes. Counts are totals so far. */
    public interface FetchProgress {
        void onProgress(String source, int done, int total, int fetched, int fresh);
    }

    public FetchResult fetch() throws Exception {
        return fetch(null);
    }

    /** Ready sources run together. Each source still paces its own requests. */
    public FetchResult fetch(FetchProgress progress) throws Exception {
        long since = Instant.now().getEpochSecond() - (long) cfg.lookbackDays * 86400;
        long fetchedAt = Instant.now().getEpochSecond();
        List<Source> ready = new ArrayList<>();
        for (Source s : sources) {
            if (s.isReady()) ready.add(s);
            else Log.warn("source %s skipped (disabled or missing credentials)", s.name());
        }
        if (ready.isEmpty()) return new FetchResult(0, 0);
        if (progress != null) progress.onProgress("", 0, ready.size(), 0, 0);

        ExecutorService pool = Executors.newFixedThreadPool(ready.size(), r -> {
            Thread t = new Thread(r, "fetch-source");
            t.setDaemon(true);
            return t;
        });
        var finished = new LinkedBlockingQueue<SourceResult>();
        try {
            for (Source s : ready) {
                pool.submit(() -> finished.add(new SourceResult(s.name(), fetchSource(s, since, fetchedAt))));
            }
            int done = 0, fetched = 0, fresh = 0;
            while (done < ready.size()) {
                SourceResult part = finished.take();
                done++;
                fetched += part.counts[0];
                fresh += part.counts[1];
                if (progress != null) progress.onProgress(part.name, done, ready.size(), fetched, fresh);
            }
            return new FetchResult(fetched, fresh);
        } finally {
            pool.shutdown();
        }
    }

    private record SourceResult(String name, int[] counts) {}

    private int[] fetchSource(Source s, long since, long fetchedAt) {
        try {
            Log.info("fetching %s ...", s.name());
            List<Post> posts = s.fetch(since);
            int fresh = store.upsertAll(posts, fetchedAt);
            Log.info("%s: %d fetched, %d new", s.name(), posts.size(), fresh);
            return new int[]{posts.size(), fresh};
        } catch (Exception e) {
            Log.error("source %s failed: %s", s.name(), e.toString());
            e.printStackTrace(System.err);
            return new int[]{0, 0};
        }
    }

    public record ClassifyResult(int classified, int opportunities, int newDemands) {}

    public boolean llmReady() {
        return llm.isReady();
    }

    public ClassifyResult classify() throws Exception {
        return classify((done, total, opps) -> {
            if (done % 25 == 0) Log.info("  %d/%d classified, %d opportunities", done, total, opps);
        });
    }

    public ClassifyResult classify(ClassifyProgress progress) throws Exception {
        if (!llm.isReady()) {
            Log.warn("LLM_API_KEY not set, skipping classification");
            return new ClassifyResult(0, 0, 0);
        }
        List<Post> pending = store.listUnclassified(cfg.maxPostsToClassifyPerRun);
        List<KnownDemand> known = new ArrayList<>(store.listKnownDemands());
        Log.info("classifying %d posts with %s, %d existing demands", pending.size(), cfg.llm.model, known.size());
        progress.onProgress(0, pending.size(), 0);
        int done = 0, found = 0, createdCount = 0, skipped = 0;
        for (Post p : pending) {
            if (store.isClassified(p.source(), p.id())) {
                skipped++;
                continue;
            }
            try {
                Opportunity raw = llm.classify(p, known);
                Opportunity o = store.canonicalize(raw);
                boolean created = store.saveOpportunity(o, Instant.now().getEpochSecond());
                done++;
                if (o.isOpportunity()) {
                    found++;
                    if (created) createdCount++;
                    remember(known, o);
                }
                progress.onProgress(done, pending.size(), found);
            } catch (Exception e) {
                Log.warn("classify failed for %s: %s", p.key(), e.getMessage());
            }
            if (cfg.llm.requestIntervalMs > 0) Thread.sleep(cfg.llm.requestIntervalMs);
        }
        if (skipped > 0) Log.info("skipped %d posts already classified for the same source and id", skipped);
        return new ClassifyResult(done, found, createdCount);
    }

    @FunctionalInterface
    public interface ClassifyProgress {
        void onProgress(int done, int total, int opportunities);
    }

    private static void remember(List<KnownDemand> known, Opportunity o) {
        if (o.painKey() == null || o.painKey().isBlank()) return;
        for (KnownDemand d : known) {
            if (d.painKey().equals(o.painKey())) return;
        }
        known.add(0, new KnownDemand(o.painKey(), o.painPoint()));
    }

    public Path report(long runStartedAt) throws Exception {
        return new ReportGenerator(cfg, store).write(runStartedAt);
    }

    public Path runAll() throws Exception {
        long started = Instant.now().getEpochSecond();
        FetchResult f = fetch();
        ClassifyResult c = classify();
        Path report = report(started);
        store.recordRun(started, Instant.now().getEpochSecond(), f.fetched(), f.newPosts(), c.classified(), c.opportunities());
        FeishuNotifier.sendIfConfigured(report);
        Log.info("run complete: fetched=%d new=%d classified=%d opportunities=%d report=%s",
                f.fetched(), f.newPosts(), c.classified(), c.opportunities(), report);
        return report;
    }
}
