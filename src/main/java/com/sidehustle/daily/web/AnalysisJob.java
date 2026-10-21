package com.sidehustle.daily.web;

import com.sidehustle.daily.pipeline.Pipeline;
import com.sidehustle.daily.util.Log;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Runs DeepSeek classification off the HTTP thread so the page can show progress. */
@Service
public class AnalysisJob {

    private final Pipeline pipeline;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analysis");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Status> status = new AtomicReference<>(Status.idle());

    public AnalysisJob(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    public Status status() {
        return status.get();
    }

    public boolean start() {
        Status current = status.get();
        if (current.running()) return false;
        if (!pipeline.llmReady()) {
            status.set(Status.failed("没有读到 LLM_API_KEY。请确认密钥在项目目录的 .env 里，然后重启服务。"));
            return false;
        }
        status.set(new Status(true, 0, 0, 0, "正在分析…"));
        executor.submit(() -> {
            try {
                var result = pipeline.classify((done, total, opps) ->
                        status.set(new Status(true, total, done, opps, "正在分析 " + done + " / " + total)));
                int merged = Math.max(0, result.opportunities() - result.newDemands());
                String detail = merged > 0
                        ? "，新归档 " + result.newDemands() + " 条，" + merged + " 条并入已有需求"
                        : "，新归档 " + result.newDemands() + " 条需求";
                status.set(new Status(false, result.classified(), result.classified(), result.opportunities(),
                        "分析完成：处理 " + result.classified() + " 条，命中需求 " + result.opportunities() + detail));
            } catch (Exception e) {
                Log.error("analysis failed: %s", e.toString());
                status.set(Status.failed("分析失败：" + e.getMessage()));
            }
        });
        return true;
    }

    public record Status(boolean running, int total, int done, int opportunities, String message) {
        static Status idle() {
            return new Status(false, 0, 0, 0, "");
        }

        static Status failed(String message) {
            return new Status(false, 0, 0, 0, message);
        }
    }
}
