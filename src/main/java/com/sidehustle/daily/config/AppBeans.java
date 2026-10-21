package com.sidehustle.daily.config;

import com.sidehustle.daily.pipeline.Pipeline;
import com.sidehustle.daily.store.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class AppBeans {

    @Bean
    AppConfig appConfig(@Value("${app.config:config.json}") String configPath) throws Exception {
        return AppConfig.load(Path.of(configPath));
    }

    @Bean(destroyMethod = "close")
    Store store(AppConfig appConfig) throws Exception {
        return new Store(Path.of(appConfig.dbPath));
    }

    @Bean
    Pipeline pipeline(AppConfig appConfig, Store store) {
        return new Pipeline(appConfig, store);
    }
}
