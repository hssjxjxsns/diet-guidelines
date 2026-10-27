package com.dietguide.adapter;

import com.dietguide.ai.AiGateway;
import com.dietguide.domain.GuidelineChunk;
import com.dietguide.service.GuidelineRetriever;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcGuidelineRetriever implements GuidelineRetriever {

    private final JdbcTemplate jdbc;
    private final AiGateway ai;
    private final JsonCodec codec;

    public JdbcGuidelineRetriever(JdbcTemplate jdbc, AiGateway ai, JsonCodec codec) {
        this.jdbc = jdbc;
        this.ai = ai;
        this.codec = codec;
    }

    @Override
    public List<GuidelineChunk> retrieve(String query, int limit) {
        List<double[]> embeddings = ai.embed(List.of(query));
        if (embeddings.isEmpty()) {
            return List.of();
        }

        double[] embedding = embeddings.get(0);
        StringBuilder vector = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index += 1) {
            if (index > 0) {
                vector.append(',');
            }
            vector.append(embedding[index]);
        }
        vector.append(']');

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, content, metadata::text as metadata_json, similarity
                from match_guideline_chunks(cast(? as vector), ?)
                """,
                vector.toString(),
                limit
        );

        List<GuidelineChunk> chunks = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) codec.readTree((String) row.get("metadata_json"));
            Number similarity = (Number) row.get("similarity");
            chunks.add(new GuidelineChunk(
                    String.valueOf(row.get("id")),
                    (String) row.get("content"),
                    metadata == null ? Map.of() : metadata,
                    similarity == null ? null : similarity.doubleValue()
            ));
        }
        return chunks;
    }
}
