package com.dietguide.adapter;

import com.dietguide.shared.Nutrients;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize json", error);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception error) {
            throw new IllegalStateException("cannot deserialize " + type.getSimpleName(), error);
        }
    }

    /** 读取对象型 JSON，失败时返回空 Map，避免调用方做类型强转。 */
    public Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            return value == null ? Map.of() : value;
        } catch (Exception error) {
            return Map.of();
        }
    }

    public Object readTree(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw new IllegalStateException("cannot parse json", error);
        }
    }

    /** 数组类字段统一以 JSON 文本存储，读取时还原为字符串列表。 */
    public List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = mapper.readValue(json, new TypeReference<List<String>>() { });
            return values == null ? List.of() : values;
        } catch (Exception error) {
            return List.of();
        }
    }

    /** 只保留 8 个已知营养指标，忽略未知键，避免脏数据进入计算。 */
    public Map<String, Double> nutrients(String json) {
        if (json == null || json.isBlank()) {
            return Nutrients.empty();
        }
        try {
            JsonNode node = mapper.readTree(json);
            Map<String, Double> result = new LinkedHashMap<>();
            for (String key : Nutrients.KEYS) {
                JsonNode value = node.get(key);
                if (value != null && value.isNumber()) {
                    result.put(key, value.asDouble());
                }
            }
            return result;
        } catch (Exception error) {
            return Nutrients.empty();
        }
    }
}
