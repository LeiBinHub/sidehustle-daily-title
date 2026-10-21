package com.sidehustle.daily;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Set;

@SpringBootApplication
public class DailyApplication {

    private static final Set<String> CLI = Set.of("run", "fetch", "classify", "report");

    public static void main(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                System.setProperty("app.config", args[i + 1]);
            }
        }
        SpringApplication app = new SpringApplication(DailyApplication.class);
        if (isCli(args)) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }

    public static boolean isCli(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("-") && CLI.contains(arg)) return true;
        }
        return false;
    }
}
