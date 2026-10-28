package com.dietguide.adapter;

import com.dietguide.domain.GuidelineChunk;
import com.dietguide.service.GuidelineRetriever;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内置数据库不依赖向量扩展：按关键词匹配指南条目，similarity 置空。
 */
@Repository
public class JdbcGuidelineRetriever implements GuidelineRetriever {

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;

    public JdbcGuidelineRetriever(JdbcTemplate jdbc, JsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public List<GuidelineChunk> retrieve(String query, int limit) {
        String keyword = query == null ? "" : query.trim();
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, content, metadata as metadata_json
                from guideline_chunks
                where status = 'active' and lower(content) like lower(?)
                limit ?
                """,
                "%" + keyword + "%",
                Math.max(limit, 1)
        );

        List<GuidelineChunk> chunks = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) codec.readTree((String) row.get("metadata_json"));
            chunks.add(new GuidelineChunk(
                    String.valueOf(row.get("id")),
                    (String) row.get("content"),
                    metadata == null ? Map.of() : metadata,
                    null
            ));
        }
        return chunks;
    }
}
