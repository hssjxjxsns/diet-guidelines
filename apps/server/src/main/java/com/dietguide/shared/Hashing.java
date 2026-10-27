package com.dietguide.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

public final class Hashing {

    private Hashing() {
    }

    /** 键排序 + 丢弃 null 的规范化 JSON，保证同一请求得到同一哈希。 */
    public static String canonicalJson(Object value, ObjectMapper mapper) {
        JsonNode node = mapper.valueToTree(value);
        return canonicalize(node).toString();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode source = (ObjectNode) node;
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            source.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                JsonNode child = source.get(name);
                if (child == null || child.isNull()) {
                    continue;
                }
                result.set(name, canonicalize(child));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) {
                result.add(canonicalize(child));
            }
            return result;
        }
        return node;
    }
}
