package com.sidehustle.daily.web;

import com.sidehustle.daily.pipeline.Pipeline;
import com.sidehustle.daily.util.Log;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Runs a fetch off the HTTP thread and keeps progress for the board page. */
@Service
public class FetchJob {

    private final Pipeline pipeline;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fetch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Status> status = new AtomicReference<>(Status.idle());

    public FetchJob(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    public Status status() {
        return status.get();
    }

    public synchronized boolean start() {
        if (status.get().running()) return false;
        status.set(new Status(true, 0, 0, 0, 0, "正在抓取…", false));
        executor.submit(this::run);
        return true;
    }

    private void run() {
        try {
            var result = pipeline.fetch((source, done, total, fetched, fresh) -> {
                String message = source == null || source.isEmpty()
                        ? "正在抓取 0/" + total
                        : "正在抓取 " + done + "/" + total + "：" + source + " 完成，已新增 " + fresh + " 条";
                status.set(new Status(true, done, total, fetched, fresh, message, false));
            });
            status.set(new Status(false, 0, 0, result.fetched(), result.newPosts(),
                    "抓取完成：拉到 " + result.fetched() + " 条，新增 " + result.newPosts() + " 条", false));
        } catch (Exception e) {
            Log.error("fetch failed: %s", e.toString());
            status.set(Status.failed("抓取失败：" + e.getMessage()));
        }
    }

    public record Status(boolean running, int done, int total, int fetched, int fresh, String message, boolean failed) {
        static Status idle() {
            return new Status(false, 0, 0, 0, 0, "", false);
        }

        static Status failed(String message) {
            return new Status(false, 0, 0, 0, 0, message, true);
        }
    }
}
