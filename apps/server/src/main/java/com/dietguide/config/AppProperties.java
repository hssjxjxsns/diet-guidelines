package com.dietguide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String jwtSecret, Zhipu zhipu) {

    public record Zhipu(
            String apiKey,
            String baseUrl,
            String chatModel,
            String embeddingModel,
            int dimensions,
            long timeoutMs,
            int maxRetries
    ) {
    }
}
