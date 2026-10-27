package com.dietguide.domain;

import java.util.Map;

public record GuidelineChunk(String id, String content, Map<String, Object> metadata, Double score) {
}
