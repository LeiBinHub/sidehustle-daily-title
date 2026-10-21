package com.sidehustle.daily.cli;

import com.sidehustle.daily.DailyApplication;
import com.sidehustle.daily.pipeline.Pipeline;
import com.sidehustle.daily.util.Log;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Keeps `java -jar ... run|fetch|classify|report` working. Web mode ignores this. */
@Component
public class CliRunner implements ApplicationRunner {

    private final Pipeline pipeline;

    public CliRunner(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String command = args.getNonOptionArgs().stream().findFirst().orElse("");
        if (!DailyApplication.isCli(new String[]{command})) return;
        switch (command) {
            case "run" -> pipeline.runAll();
            case "fetch" -> {
                var r = pipeline.fetch();
                Log.info("fetch complete: fetched=%d new=%d", r.fetched(), r.newPosts());
            }
            case "classify" -> {
                var r = pipeline.classify();
                Log.info("classify complete: classified=%d opportunities=%d", r.classified(), r.opportunities());
            }
            case "report" -> {
                var p = pipeline.report(Instant.now().getEpochSecond() - 86400);
                Log.info("report written: %s", p);
            }
            default -> throw new IllegalStateException(command);
        }
        System.exit(0);
    }
}
