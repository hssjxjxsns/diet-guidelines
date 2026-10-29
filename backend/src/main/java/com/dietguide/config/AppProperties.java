package com.dietguide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Zhipu zhipu) {

    public record Zhipu(
            String apiKey,
            String baseUrl,
            String chatModel,
            String embeddingModel,
            int dimensions,
            long timeoutMs,
            int maxRetries,
            /** 推理强度，glm-5.x 始终思考，取值 low / high / max。 */
            String reasoningEffort
    ) {
    }
}
