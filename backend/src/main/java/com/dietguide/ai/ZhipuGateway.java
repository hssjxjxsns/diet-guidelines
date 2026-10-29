package com.dietguide.ai;

import com.dietguide.config.AppProperties;
import com.dietguide.shared.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ZhipuGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(ZhipuGateway.class);

    private final AppProperties.Zhipu config;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ZhipuGateway(AppProperties properties, ObjectMapper mapper) {
        this.config = properties.zhipu();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMs()))
                .build();
    }

    @Override
    public <T> T chatJson(ChatRequest request, Class<T> type) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw AppException.aiUnavailable("ZHIPU_API_KEY is not configured");
        }

        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.chatModel());
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", request.system()),
                    Map.of("role", "user", "content", buildUserContent(request))
            ));
            payload.put("temperature", request.temperature() == null ? 0.3 : request.temperature());
            payload.put("max_tokens", request.maxOutputTokens());
            payload.put("response_format", Map.of("type", "json_object"));
            payload.put("reasoning_effort", config.reasoningEffort() == null ? "low" : config.reasoningEffort());

            String body = post("/chat/completions", payload);
            JsonNode root = mapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            String finishReason = root.path("choices").path(0).path("finish_reason").asText("");
            int reasoningTokens = root.path("usage").path("completion_tokens_details").path("reasoning_tokens").asInt(0);

            if (content.isBlank()) {
                log.warn("ai_call scene={} model={} status=empty_content finishReason={} reasoningTokens={}",
                        request.scene(), config.chatModel(), finishReason, reasoningTokens);
                throw AppException.aiInvalidOutput(
                        finishReason.equals("length")
                                ? "AI 输出被截断，推理占满了 token 上限，请调大 max_tokens"
                                : "AI 返回了空内容，请稍后重试或更换模型",
                        null);
            }

            log.info("ai_call scene={} model={} promptTokens={} completionTokens={} latencyMs={} status=ok",
                    request.scene(),
                    config.chatModel(),
                    root.path("usage").path("prompt_tokens").asInt(0),
                    root.path("usage").path("completion_tokens").asInt(0),
                    System.currentTimeMillis() - startedAt);

            String json = extractJsonObject(content);
            try {
                T parsed = mapper.readerFor(type).readValue(json);
                if (parsed == null) {
                    throw AppException.aiInvalidOutput("AI output is empty", null);
                }
                return parsed;
            } catch (AppException error) {
                throw error;
            } catch (Exception error) {
                throw AppException.aiInvalidOutput("AI output does not match schema", error.getMessage());
            }
        } catch (AppException error) {
            log.warn("ai_call scene={} status=error message={}", request.scene(), error.getMessage());
            throw error;
        } catch (Exception error) {
            log.warn("ai_call scene={} status=error message={}", request.scene(), error.getMessage());
            throw AppException.aiUnavailable(error.getMessage() == null ? "AI request failed" : error.getMessage());
        }
    }

    @Override
    public List<double[]> embed(List<String> texts) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw AppException.aiUnavailable("ZHIPU_API_KEY is not configured");
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        if (texts.size() > 64) {
            throw AppException.aiInvalidOutput("Embedding batch must be <= 64 texts", null);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.embeddingModel());
        payload.put("input", texts);
        payload.put("dimensions", config.dimensions());

        try {
            String body = post("/embeddings", payload);
            JsonNode data = mapper.readTree(body).path("data");
            List<double[]> result = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                double[] values = new double[embedding.size()];
                for (int index = 0; index < embedding.size(); index += 1) {
                    values[index] = embedding.get(index).asDouble();
                }
                result.add(values);
            }
            return result;
        } catch (AppException error) {
            throw error;
        } catch (Exception error) {
            throw AppException.aiUnavailable(error.getMessage());
        }
    }

    private Object buildUserContent(ChatRequest request) {
        if (request.images() == null || request.images().isEmpty()) {
            return request.user();
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", request.user()));
        for (String url : request.images()) {
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
        }
        return parts;
    }

    private String post(String path, Map<String, Object> payload) {
        Exception lastError = null;

        for (int attempt = 0; attempt <= Math.max(config.maxRetries(), 0); attempt += 1) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                        .timeout(Duration.ofMillis(config.timeoutMs()))
                        .header("Authorization", "Bearer " + config.apiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }

                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                lastError = new IllegalStateException("Zhipu API " + response.statusCode() + ": " + response.body());
                if (!retryable) {
                    break;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                lastError = error;
                break;
            } catch (Exception error) {
                lastError = error;
            }
        }

        String message = lastError == null ? "Zhipu API request failed" : lastError.getMessage();
        throw new IllegalStateException(message, lastError);
    }

    /** 去掉 Markdown 代码块并截取最外层 JSON 对象。 */
    static String extractJsonObject(String content) {
        if (content == null) {
            throw AppException.aiInvalidOutput("AI output is empty", null);
        }
        String trimmed = content.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) {
            throw AppException.aiInvalidOutput("AI output is not a JSON object", null);
        }
        return trimmed.substring(start, end + 1);
    }
}
