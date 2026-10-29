package com.dietguide.service;

import com.dietguide.adapter.JsonCodec;
import com.dietguide.ai.AiGateway;
import com.dietguide.shared.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 把内置膳食指南语料写入本地库，并调用嵌入模型把向量一并存在同一个 H2 文件里。
 * 已索引且内容未变的条目会跳过，重启不会重复消耗额度。
 */
@Component
public class GuidelineIndexer {

    private static final Logger log = LoggerFactory.getLogger(GuidelineIndexer.class);
    private static final String CORPUS = "guidelines/dietary-guidelines.json";
    private static final int BATCH_SIZE = 32;

    private final JdbcTemplate jdbc;
    private final AiGateway gateway;
    private final JsonCodec codec;
    private final ObjectMapper mapper;
    private final String embeddingModel;

    public GuidelineIndexer(
            JdbcTemplate jdbc,
            AiGateway gateway,
            JsonCodec codec,
            ObjectMapper mapper,
            com.dietguide.config.AppProperties properties
    ) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.codec = codec;
        this.mapper = mapper;
        this.embeddingModel = properties.zhipu().embeddingModel();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexInBackground() {
        CompletableFuture.runAsync(this::indexSafely);
    }

    private void indexSafely() {
        try {
            index();
        } catch (Exception error) {
            log.warn("guideline_index status=failed message={}", error.getMessage());
        }
    }

    private void index() {
        List<CorpusItem> corpus = loadCorpus();
        if (corpus.isEmpty()) {
            return;
        }

        List<Pending> pending = new ArrayList<>();
        for (CorpusItem item : corpus) {
            String text = item.title() + "。" + item.content();
            String hash = sha256(text);
            Map<String, Object> existing = jdbc.query(
                    "select content_hash, embedding from guideline_chunks where id = ?",
                    resultSet -> resultSet.next()
                            ? Map.of(
                                    "hash", resultSet.getString("content_hash") == null ? "" : resultSet.getString("content_hash"),
                                    "embedding", resultSet.getString("embedding") == null ? "" : resultSet.getString("embedding"))
                            : null,
                    item.id()
            );
            if (existing != null
                    && hash.equals(existing.get("hash"))
                    && !((String) existing.get("embedding")).isBlank()) {
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("title", item.title());
            metadata.put("tags", item.tags() == null ? List.of() : item.tags());

            jdbc.update(
                    """
                    merge into guideline_chunks (id, content, metadata, content_hash, status)
                    key (id) values (?, ?, ?, ?, 'active')
                    """,
                    item.id(), item.content(), codec.write(metadata), hash
            );
            jdbc.update(
                    "update guideline_chunks set embedding = null, embedded_at = null where id = ?",
                    item.id()
            );
            pending.add(new Pending(item.id(), text));
        }

        if (pending.isEmpty()) {
            log.info("guideline_index status=skipped indexed={} reason=up_to_date", corpus.size());
            return;
        }

        int indexed = 0;
        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            List<Pending> batch = pending.subList(start, Math.min(start + BATCH_SIZE, pending.size()));
            try {
                List<String> texts = batch.stream().map(Pending::text).toList();
                List<double[]> vectors = gateway.embed(texts);
                for (int index = 0; index < batch.size(); index += 1) {
                    jdbc.update(
                            """
                            update guideline_chunks
                            set embedding = ?, embedding_model = ?, embedded_at = current_timestamp
                            where id = ?
                            """,
                            codec.write(vectors.get(index)),
                            embeddingModel,
                            batch.get(index).id()
                    );
                    indexed += 1;
                }
            } catch (AppException error) {
                log.warn("guideline_index status=embed_failed message={} pending={}", error.getMessage(), pending.size());
                return;
            }
        }
        log.info("guideline_index status=ok total={} indexed={}", corpus.size(), indexed);
    }

    private List<CorpusItem> loadCorpus() {
        try (InputStream stream = new ClassPathResource(CORPUS, GuidelineIndexer.class.getClassLoader()).getInputStream()) {
            List<CorpusItem> items = mapper.readValue(stream, new TypeReference<List<CorpusItem>>() { });
            return items == null ? List.of() : items;
        } catch (Exception error) {
            log.warn("guideline_index status=corpus_failed message={}", error.getMessage());
            return List.of();
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception error) {
            return String.valueOf(value.hashCode());
        }
    }

    public record CorpusItem(String id, String title, String content, List<String> tags) {
    }

    private record Pending(String id, String text) {
    }
}
