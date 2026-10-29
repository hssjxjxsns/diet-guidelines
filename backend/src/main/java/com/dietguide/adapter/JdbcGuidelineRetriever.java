package com.dietguide.adapter;

import com.dietguide.ai.AiGateway;
import com.dietguide.domain.GuidelineChunk;
import com.dietguide.service.GuidelineRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指南检索：向量存在本地 H2 的 guideline_chunks.embedding 列里，
 * 查询时先在内存算余弦相似度；向量缺失或调用失败时退回关键词匹配。
 */
@Repository
public class JdbcGuidelineRetriever implements GuidelineRetriever {

    private static final Logger log = LoggerFactory.getLogger(JdbcGuidelineRetriever.class);
    private static final double MIN_SIMILARITY = 0.25;
    private static final int QUERY_CACHE_LIMIT = 32;

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;
    private final AiGateway gateway;
    private final Map<String, double[]> queryVectors = new ConcurrentHashMap<>();

    public JdbcGuidelineRetriever(JdbcTemplate jdbc, JsonCodec codec, AiGateway gateway) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.gateway = gateway;
    }

    @Override
    public List<GuidelineChunk> retrieve(String query, int limit) {
        int size = Math.max(limit, 1);
        String keyword = query == null ? "" : query.trim();

        List<VectorEntry> entries = loadVectors();
        if (!entries.isEmpty()) {
            double[] queryVector = embedQuery(keyword);
            if (queryVector != null) {
                List<ScoredEntry> scored = new ArrayList<>();
                for (VectorEntry entry : entries) {
                    double similarity = cosine(queryVector, entry.vector());
                    if (similarity >= MIN_SIMILARITY) {
                        scored.add(new ScoredEntry(entry, similarity));
                    }
                }
                scored.sort(Comparator.comparingDouble(ScoredEntry::similarity).reversed());
                if (!scored.isEmpty()) {
                    log.info("guideline_retrieve mode=vector candidates={} top={} maxScore={}",
                            entries.size(), Math.min(size, scored.size()),
                            String.format("%.2f", scored.get(0).similarity()));
                }
                return scored.stream()
                        .limit(size)
                        .map(item -> new GuidelineChunk(
                                item.entry().id(),
                                item.entry().content(),
                                item.entry().metadata(),
                                item.similarity()
                        ))
                        .toList();
            }
        }
        return keywordFallback(keyword, size);
    }

    private List<VectorEntry> loadVectors() {
        List<VectorEntry> entries = new ArrayList<>();
        jdbc.query(
                """
                select id, content, metadata as metadata_json, embedding
                from guideline_chunks
                where status = 'active' and embedding is not null
                """,
                resultSet -> {
                    String embedding = resultSet.getString("embedding");
                    double[] vector = readVector(embedding);
                    if (vector.length > 0) {
                        Map<String, Object> metadata = readMetadata(resultSet.getString("metadata_json"));
                        entries.add(new VectorEntry(
                                resultSet.getString("id"),
                                resultSet.getString("content"),
                                metadata,
                                vector
                        ));
                    }
                }
        );
        return entries;
    }

    private double[] embedQuery(String query) {
        if (query.isBlank()) {
            return null;
        }
        double[] cached = queryVectors.get(query);
        if (cached != null) {
            return cached;
        }
        try {
            List<double[]> vectors = gateway.embed(List.of(query));
            if (vectors.isEmpty()) {
                return null;
            }
            if (queryVectors.size() >= QUERY_CACHE_LIMIT) {
                queryVectors.clear();
            }
            queryVectors.put(query, vectors.get(0));
            return vectors.get(0);
        } catch (Exception error) {
            log.warn("guideline_retrieve status=embed_failed message={}", error.getMessage());
            return null;
        }
    }

    private List<GuidelineChunk> keywordFallback(String keyword, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, content, metadata as metadata_json
                from guideline_chunks
                where status = 'active' and lower(content) like lower(?)
                limit ?
                """,
                "%" + keyword + "%",
                limit
        );

        List<GuidelineChunk> chunks = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            chunks.add(new GuidelineChunk(
                    String.valueOf(row.get("id")),
                    (String) row.get("content"),
                    readMetadata((String) row.get("metadata_json")),
                    null
            ));
        }
        return chunks;
    }

    private Map<String, Object> readMetadata(String json) {
        return codec.map(json);
    }

    private double[] readVector(String json) {
        if (json == null || json.isBlank()) {
            return new double[0];
        }
        try {
            double[] values = codec.read(json, double[].class);
            return values == null ? new double[0] : values;
        } catch (Exception error) {
            return new double[0];
        }
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index += 1) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record VectorEntry(String id, String content, Map<String, Object> metadata, double[] vector) {
    }

    private record ScoredEntry(VectorEntry entry, double similarity) {
    }
}
